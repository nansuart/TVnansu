<?php
// nansu tv web: shared helpers (session, password, JSON).
declare(strict_types=1);

const DATA_DIR = __DIR__ . '/data';
const PASS_FILE = DATA_DIR . '/password.php';
const USER_AGENT = 'TVnansu/1.0 (Linux; Android TV)';

function nt_session(): void
{
    if (session_status() === PHP_SESSION_ACTIVE) return;
    $secure = (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off')
        || (($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https');
    $life = 60 * 60 * 24 * 120;
    ini_set('session.gc_maxlifetime', (string)$life);
    session_name('nansutv');
    session_set_cookie_params([
        'lifetime' => $life,
        'path' => '/',
        'httponly' => true,
        'samesite' => 'Lax',
        'secure' => $secure,
    ]);
    session_start();
}

function nt_authed(): bool
{
    nt_session();
    return !empty($_SESSION['nt_ok']);
}

function nt_require_auth(): void
{
    if (!nt_authed()) nt_json(['error' => 'auth'], 401);
    // Release the session lock so parallel requests (video segments) don't queue.
    session_write_close();
}

function nt_json($data, int $code = 200): void
{
    http_response_code($code);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function nt_stored_hash(): ?string
{
    if (!is_file(PASS_FILE)) return null;
    $h = include PASS_FILE;
    return is_string($h) && $h !== '' ? $h : null;
}

function nt_save_hash(string $hash): bool
{
    if (!is_dir(DATA_DIR)) @mkdir(DATA_DIR, 0750, true);
    $php = "<?php\n// Contraseña de acceso a nansu tv (cifrada). Bórralo para crear una nueva.\nreturn " . var_export($hash, true) . ";\n";
    return file_put_contents(PASS_FILE, $php, LOCK_EX) !== false;
}

function nt_body(): array
{
    $raw = file_get_contents('php://input') ?: '';
    $data = json_decode($raw, true);
    return is_array($data) ? $data : [];
}
