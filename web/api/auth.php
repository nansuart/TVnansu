<?php
// Access to the site: first visit creates the password, later visits log in.
declare(strict_types=1);
require __DIR__ . '/common.php';

hi_session();
$action = $_GET['a'] ?? 'status';
$hash = hi_stored_hash();

switch ($action) {
    case 'status':
        hi_json([
            'setup' => $hash === null,
            'authed' => !empty($_SESSION['hi_ok']),
            'php' => PHP_VERSION,
            'curl' => function_exists('curl_init'),
        ]);

    case 'setup':
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') hi_json(['error' => 'method'], 405);
        if ($hash !== null) hi_json(['error' => 'exists'], 409);
        $pass = (string)(hi_body()['password'] ?? '');
        if (mb_strlen($pass) < 6) hi_json(['error' => 'short'], 400);
        if (!hi_save_hash(password_hash($pass, PASSWORD_DEFAULT))) hi_json(['error' => 'write'], 500);
        session_regenerate_id(true);
        $_SESSION['hi_ok'] = true;
        hi_json(['ok' => true]);

    case 'login':
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') hi_json(['error' => 'method'], 405);
        if ($hash === null) hi_json(['error' => 'setup'], 409);
        $pass = (string)(hi_body()['password'] ?? '');
        if (!password_verify($pass, $hash)) {
            sleep(1); // slows down guessing
            hi_json(['error' => 'wrong'], 403);
        }
        session_regenerate_id(true);
        $_SESSION['hi_ok'] = true;
        hi_json(['ok' => true]);

    case 'logout':
        $_SESSION = [];
        session_destroy();
        hi_json(['ok' => true]);

    default:
        hi_json(['error' => 'action'], 400);
}
