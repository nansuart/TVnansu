<?php
// Hispano IPTV web: channel logos from the open tv-logos collection (github.com/tv-logo/tv-logos).
// The first time a logo is asked for, the hosting downloads it and keeps a copy in
// api/data/logos; after that it is served from the hosting and the browser keeps it a month.
// Only names listed in lib/logos/index.json are accepted.
declare(strict_types=1);
require __DIR__ . '/common.php';

hi_require_auth();

$p = (string)($_GET['p'] ?? '');
if (!preg_match('~^([a-z0-9-]+)/([a-z0-9][a-z0-9-]*)$~', $p, $m)) hi_json(['error' => 'name'], 400);
[$all, $country, $name] = $m;

$dir = __DIR__ . '/data/logos/' . $country;
$file = $dir . '/' . $name . '.png';

function hi_send_logo(string $file): void
{
    $mtime = filemtime($file) ?: time();
    $etag = '"' . dechex($mtime) . '-' . dechex(filesize($file) ?: 0) . '"';
    header('Cache-Control: private, max-age=2592000, immutable');
    header('ETag: ' . $etag);
    if (trim($_SERVER['HTTP_IF_NONE_MATCH'] ?? '') === $etag) {
        http_response_code(304);
        exit;
    }
    header('Content-Type: image/png');
    header('Content-Length: ' . filesize($file));
    readfile($file);
    exit;
}

if (is_file($file)) hi_send_logo($file);

// Is it a logo of the collection?
$index = json_decode((string)@file_get_contents(dirname(__DIR__) . '/lib/logos/index.json'), true);
if (!is_array($index) || !in_array($name, $index['c'][$country] ?? [], true)) hi_json(['error' => 'unknown'], 404);

// Don't ask GitHub again for an hour after it failed.
$fail = $file . '.fail';
if (is_file($fail) && time() - (int)filemtime($fail) < 3600) hi_json(['error' => 'unavailable'], 404);

if (!is_dir($dir)) @mkdir($dir, 0755, true);
$url = 'https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries/' . rawurlencode($country) . '/' . rawurlencode($name) . '.png';
$ch = curl_init($url);
curl_setopt_array($ch, [
    CURLOPT_RETURNTRANSFER => true,
    CURLOPT_FOLLOWLOCATION => true,
    CURLOPT_MAXREDIRS => 3,
    CURLOPT_CONNECTTIMEOUT => 6,
    CURLOPT_TIMEOUT => 15,
    CURLOPT_USERAGENT => 'hispano-iptv-web',
]);
$png = curl_exec($ch);
$status = (int)curl_getinfo($ch, CURLINFO_RESPONSE_CODE);
curl_close($ch);

if ($status !== 200 || !is_string($png) || strlen($png) > 2 * 1024 * 1024 || substr($png, 0, 8) !== "\x89PNG\r\n\x1a\n") {
    @touch($fail);
    hi_json(['error' => 'unavailable'], 404);
}
$tmp = $file . '.' . getmypid() . '.tmp';
if (@file_put_contents($tmp, $png) !== false && @rename($tmp, $file)) {
    @unlink($fail);
    hi_send_logo($file);
}
@unlink($tmp);
header('Content-Type: image/png');
header('Cache-Control: private, max-age=86400');
echo $png;
