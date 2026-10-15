<?php
// Access to the site: first visit creates the password, later visits log in.
declare(strict_types=1);
require __DIR__ . '/common.php';

nt_session();
$action = $_GET['a'] ?? 'status';
$hash = nt_stored_hash();

switch ($action) {
    case 'status':
        nt_json([
            'setup' => $hash === null,
            'authed' => !empty($_SESSION['nt_ok']),
            'php' => PHP_VERSION,
            'curl' => function_exists('curl_init'),
        ]);

    case 'setup':
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') nt_json(['error' => 'method'], 405);
        if ($hash !== null) nt_json(['error' => 'exists'], 409);
        $pass = (string)(nt_body()['password'] ?? '');
        if (mb_strlen($pass) < 6) nt_json(['error' => 'short'], 400);
        if (!nt_save_hash(password_hash($pass, PASSWORD_DEFAULT))) nt_json(['error' => 'write'], 500);
        session_regenerate_id(true);
        $_SESSION['nt_ok'] = true;
        nt_json(['ok' => true]);

    case 'login':
        if ($_SERVER['REQUEST_METHOD'] !== 'POST') nt_json(['error' => 'method'], 405);
        if ($hash === null) nt_json(['error' => 'setup'], 409);
        $pass = (string)(nt_body()['password'] ?? '');
        if (!password_verify($pass, $hash)) {
            sleep(1); // slows down guessing
            nt_json(['error' => 'wrong'], 403);
        }
        session_regenerate_id(true);
        $_SESSION['nt_ok'] = true;
        nt_json(['ok' => true]);

    case 'logout':
        $_SESSION = [];
        session_destroy();
        nt_json(['ok' => true]);

    default:
        nt_json(['error' => 'action'], 400);
}
