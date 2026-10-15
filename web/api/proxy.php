<?php
// nansu tv web: fetches lists, guide and video from the IPTV provider on behalf of
// the browser (which cannot call the provider directly because of CORS and
// http/https mixing). Only for logged-in users, never towards private networks.
declare(strict_types=1);
require __DIR__ . '/common.php';

nt_require_auth();

$raw = (string)($_GET['u'] ?? '');
$url = base64_decode(strtr($raw, '-_', '+/'), true);
if ($url === false || $url === '') nt_json(['error' => 'url'], 400);

/** True when the URL is http(s) and its host resolves only to public addresses. */
function nt_allowed(string $url): bool
{
    if (getenv('NT_DEV') === '1') return (bool)preg_match('~^https?://~i', $url); // local testing only
    $p = parse_url($url);
    if (!$p || !in_array(strtolower($p['scheme'] ?? ''), ['http', 'https'], true) || empty($p['host'])) return false;
    $host = trim($p['host'], '[]');
    $ips = filter_var($host, FILTER_VALIDATE_IP) ? [$host] : (gethostbynamel($host) ?: []);
    if (!$ips) return false;
    foreach ($ips as $ip) {
        if (!filter_var($ip, FILTER_VALIDATE_IP, FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE)) return false;
    }
    return true;
}

function nt_resolve(string $base, string $rel): string
{
    if (preg_match('~^[a-z][a-z0-9+.-]*://~i', $rel)) return $rel;
    $b = parse_url($base);
    $scheme = $b['scheme'] ?? 'http';
    $auth = $scheme . '://' . $b['host'] . (isset($b['port']) ? ':' . $b['port'] : '');
    if (str_starts_with($rel, '//')) return $scheme . ':' . $rel;
    if (str_starts_with($rel, '/')) return $auth . $rel;
    $path = $b['path'] ?? '/';
    $dir = substr($path, 0, (int)strrpos($path, '/') + 1);
    return $auth . $dir . $rel;
}

function nt_proxied(string $abs): string
{
    return 'proxy.php?u=' . rtrim(strtr(base64_encode($abs), '+/', '-_'), '=');
}

/** HLS playlists have #EXT-X- tags; IPTV M3U lists (only #EXTINF) must not be rewritten. */
function nt_is_hls(string $body): bool
{
    return (bool)preg_match('/#EXT-X-(TARGETDURATION|STREAM-INF|MEDIA-SEQUENCE|KEY|MAP|VERSION|ENDLIST|PLAYLIST-TYPE|I-FRAME|MEDIA:)/', $body);
}

/** Points every URI of an HLS playlist back through this proxy. */
function nt_rewrite_m3u8(string $body, string $base): string
{
    $out = [];
    foreach (preg_split("/\r\n|\n|\r/", $body) as $line) {
        $t = trim($line);
        if ($t === '') { $out[] = $line; continue; }
        if ($t[0] === '#') {
            $out[] = preg_replace_callback('/URI="([^"]+)"/', fn($m) => 'URI="' . nt_proxied(nt_resolve($base, $m[1])) . '"', $line);
        } else {
            $out[] = nt_proxied(nt_resolve($base, $t));
        }
    }
    return implode("\n", $out);
}

@set_time_limit(0);
@ini_set('zlib.output_compression', '0');
while (ob_get_level() > 0) @ob_end_clean();

$range = $_SERVER['HTTP_RANGE'] ?? null;
// HEAD (some players ask only for the size): headers only, never the file.
$head = strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'HEAD';
// A whole-file request without Range for a big file is a probe that the player
// abandons at once; never pull more than this for it (the hosting may not notice
// that the browser left).
const NT_UNRANGED_CAP = 64 * 1024 * 1024;
$sent = 0;
// Movies and episodes: the browser asks for "the rest of the file" (bytes=N-). Serving it
// in pieces keeps each request short: shared hostings buffer long responses, and a
// download left running keeps a second connection open with the provider (which then
// refuses the next one). The browser asks for the next piece by itself.
// Right after a jump in the timeline (cookie set by the player) the first piece is small,
// so the picture starts sooner; then pieces are big again.
$chunk = (int)(getenv('NT_CHUNK') ?: 16 * 1024 * 1024);
// The speed test in Ajustes can choose another size for this browser (1–64 MB).
$custom = (int)($_COOKIE['nt_chunk'] ?? 0);
if ($custom >= 1024 * 1024 && $custom <= 64 * 1024 * 1024) $chunk = $custom;
$seekHint = isset($_COOKIE['nt_seek']);
if ($seekHint) $chunk = min($chunk, (int)(getenv('NT_SEEK_CHUNK') ?: 2 * 1024 * 1024));
$usedHint = false;
// Safari's video player expects "the rest of the file" when it asks for it: no pieces there.
$ua = (string)($_SERVER['HTTP_USER_AGENT'] ?? '');
$safari = str_contains($ua, 'AppleCoreMedia')
    || (str_contains($ua, 'Safari') && !preg_match('/Chrome|Chromium|CriOS|Edg|OPR|Firefox|FxiOS|Android/', $ua));
if (!$safari && $range && preg_match('/^bytes=(\d+)-$/', trim($range), $m)) {
    $range = 'bytes=' . $m[1] . '-' . ((int)$m[1] + $chunk - 1);
    $usedHint = $seekHint;
}
$current = $url;
$isPlaylistUrl = fn(string $u) => (bool)preg_match('~\.m3u8($|\?)~i', $u);

// One video request at a time towards the provider. Players (the browser's and the MKV
// one) sometimes ask for two pieces at once, or leave one running when the user jumps;
// most IPTV accounts allow a single connection and refuse (or block for a while) the
// second. Requests for pieces of a file wait here for the previous one to finish.
$lock = null;
if ($range && !$head) {
    $dir = DATA_DIR . '/locks';
    if (!is_dir($dir)) @mkdir($dir, 0700, true);
    $lock = @fopen($dir . '/' . md5((string)parse_url($url, PHP_URL_HOST)) . '.lock', 'c+');
    if ($lock) {
        $until = microtime(true) + 25;
        while (!flock($lock, LOCK_EX | LOCK_NB)) {
            if (microtime(true) > $until || connection_aborted()) break; // never wait forever
            usleep(100000);
        }
        // The provider takes a moment to notice that the previous connection closed.
        $last = (float)stream_get_contents($lock, 32, 0);
        $gap = microtime(true) - $last;
        if ($last > 0 && $gap < 0.5) usleep((int)((0.5 - $gap) * 1000000));
    }
}
$refusedTries = 0;

for ($hop = 0; $hop < 6; $hop++) {
    if (!nt_allowed($current)) nt_json(['error' => 'blocked'], 403);

    $status = 0;
    $headers = [];
    $location = null;
    $started = false;
    $playlist = $isPlaylistUrl($current);
    $buffer = '';
    $refused = false;

    $ch = curl_init($current);
    $reqHeaders = ['Accept: */*', 'Connection: close'];
    if ($range) $reqHeaders[] = 'Range: ' . $range;
    curl_setopt_array($ch, [
        CURLOPT_FOLLOWLOCATION => false,
        CURLOPT_NOBODY => $head,
        CURLOPT_USERAGENT => USER_AGENT,
        CURLOPT_HTTPHEADER => $reqHeaders,
        CURLOPT_CONNECTTIMEOUT => 15,
        CURLOPT_LOW_SPEED_LIMIT => 1,
        CURLOPT_LOW_SPEED_TIME => 40,
        CURLOPT_ENCODING => '',
        CURLOPT_SSL_VERIFYPEER => false,
        CURLOPT_SSL_VERIFYHOST => 0,
        CURLOPT_PROTOCOLS => CURLPROTO_HTTP | CURLPROTO_HTTPS,
        CURLOPT_HEADERFUNCTION => function ($ch, $h) use (&$status, &$headers, &$location) {
            $len = strlen($h);
            if (preg_match('~^HTTP/\S+\s+(\d{3})~', $h, $m)) {
                $status = (int)$m[1];
                $headers = [];
                return $len;
            }
            $parts = explode(':', $h, 2);
            if (count($parts) === 2) {
                $name = strtolower(trim($parts[0]));
                $value = trim($parts[1]);
                $headers[$name] = $value;
                if ($name === 'location') $location = $value;
            }
            return $len;
        },
        CURLOPT_WRITEFUNCTION => function ($ch, $data) use (&$status, &$headers, &$started, &$playlist, &$buffer, $usedHint, $range, &$sent, &$refused, $refusedTries) {
            if ($status >= 300 && $status < 400) return strlen($data); // redirect body: ignore
            // "Too many connections" for a piece of a file: try again in a moment (below).
            if (!$started && $range && $refusedTries < 2 && in_array($status, [403, 429, 458, 503, 509], true)) {
                $refused = true;
                return strlen($data);
            }
            $type = strtolower($headers['content-type'] ?? '');
            if (!$started && (str_contains($type, 'mpegurl'))) $playlist = true;
            if ($playlist) {
                $buffer .= $data;
                // A big file without HLS tags is an M3U channel list: stream it as is.
                if (strlen($buffer) < 256 * 1024 || nt_is_hls($buffer)) {
                    return strlen(strlen($buffer) > 16 * 1024 * 1024 ? '' : $data);
                }
                $playlist = false;
                $data = $buffer;
                $buffer = '';
            }
            if (!$started) {
                $started = true;
                http_response_code($status ?: 200);
                foreach (['content-type', 'content-range', 'accept-ranges', 'last-modified'] as $h) {
                    if (isset($headers[$h])) header(ucwords($h, '-') . ': ' . $headers[$h]);
                }
                if (isset($headers['content-length']) && !isset($headers['content-encoding'])) {
                    header('Content-Length: ' . $headers['content-length']);
                }
                // Pictures can be cached by the browser for a week; the rest is live data.
                $isImage = str_starts_with(strtolower($headers['content-type'] ?? ''), 'image/');
                header($isImage ? 'Cache-Control: private, max-age=604800' : 'Cache-Control: no-store');
                header('X-Accel-Buffering: no');
                // The small piece was used: the next ones are big again.
                if ($usedHint) header('Set-Cookie: nt_seek=; Max-Age=0; Path=/; SameSite=Lax', false);
            }
            echo $data;
            @flush();
            $sent += strlen($data);
            if (!$range && !$playlist && (int)($headers['content-length'] ?? 0) > NT_UNRANGED_CAP && $sent >= NT_UNRANGED_CAP) return 0;
            return connection_aborted() ? 0 : strlen($data);
        },
    ]);
    curl_exec($ch);
    if ($lock && !$refused) {
        @ftruncate($lock, 0);
        @rewind($lock);
        @fwrite($lock, (string)microtime(true));
        @fflush($lock);
    }
    $err = curl_errno($ch);
    $errMsg = curl_error($ch);
    $effective = curl_getinfo($ch, CURLINFO_EFFECTIVE_URL) ?: $current;
    curl_close($ch);

    if ($status >= 300 && $status < 400 && $location) {
        $current = nt_resolve($current, $location);
        continue;
    }
    if ($refused && !$started) {
        $refusedTries++;
        $hop--;
        usleep(900000 * $refusedTries);
        continue;
    }
    if ($head) {
        if ($status === 0) nt_json(['error' => 'connect', 'code' => $err, 'msg' => $errMsg], 502);
        http_response_code($status);
        foreach (['content-type', 'content-length', 'content-range', 'accept-ranges', 'last-modified'] as $h) {
            if (isset($headers[$h])) header(ucwords($h, '-') . ': ' . $headers[$h]);
        }
        header('Cache-Control: no-store');
        exit;
    }
    if ($playlist && !$started) {
        if ($status === 0) nt_json(['error' => 'connect', 'code' => $err, 'msg' => $errMsg], 502);
        http_response_code($status);
        header('Cache-Control: no-store');
        if (nt_is_hls($buffer)) {
            header('Content-Type: application/vnd.apple.mpegurl');
            echo nt_rewrite_m3u8($buffer, $effective);
        } else {
            header('Content-Type: text/plain; charset=utf-8');
            echo $buffer;
        }
        exit;
    }
    if (!$started) {
        if ($status === 0) nt_json(['error' => 'connect', 'code' => $err, 'msg' => $errMsg], 502);
        http_response_code($status);
        header('Cache-Control: no-store');
    }
    exit;
}
nt_json(['error' => 'redirects'], 508);
