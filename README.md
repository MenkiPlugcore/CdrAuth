# CdrAuth

Crossplay PIN authentication plugin for Paper servers.

CdrAuth provides a GUI-based register/login flow inspired by AuthMe. Java players use an inventory PIN keypad; Bedrock players use a native Floodgate form when Floodgate is available.

## v0.5.0 Admin GUI

Admins can now manage registered CdrAuth accounts through an inventory GUI:

```text
/cdrauth admin
/cdrauth admin <player|uuid>
```

`/cdrauth admin` opens the paginated account browser. `/cdrauth admin <player|uuid>` acts as a direct search and opens the matching account detail screen.

Features:

- 45 registered accounts per page.
- Player-head account entries with online state, trusted-IP state, and PIN state.
- Previous/next page navigation and refresh.
- Detailed account view with UUID, registration date, auth session state, trusted-IP state, and PIN state.
- Reset Trusted IP action.
- Reset PIN action.
- Unregister account action.
- Confirmation screen before every account-changing action.
- Search hint plus command-based direct lookup by username or UUID.
- Existing `/cdrauth status`, `resetip`, `resetpin`, and `unreg` commands remain available.

Admin GUI actions intentionally dispatch the same administrative commands used by the command-line interface. This keeps the v0.3.0 safety rules in one code path: reset-IP is still blocked while reset-PIN is pending, reset-PIN still requires a bound trusted IP, and unregister still forces a fresh registration when the target is online.

All Admin GUI titles, labels, lore, and confirmation text are configurable in `config.yml` under `messages.admin-gui-*`.

Permission: `cdrauth.admin` (default: op)

## v0.4.0 Change PIN

Players can securely change their own PIN without asking an admin:

```text
/pin
/changepin
```

Permission: `cdrauth.changepin` (default: true)

Flow:

```text
/pin
  -> verify old PIN
  -> enter new PIN
  -> confirm new PIN
  -> PIN updated
```

The new PIN cannot be identical to the old PIN. Wrong old-PIN attempts use progressive cooldown, per-session attempt limits, and a dedicated brute-force bucket so PIN-change attacks do not mix with normal login counters.

By default, self-service PIN changes are allowed only from the account's trusted IP:

```yaml
security:
  change-pin:
    require-trusted-ip: true
```

Set this to `false` if you want an already authenticated session from another IP to be allowed to change its PIN. The old PIN is still required even when this setting is disabled.

Self-service PIN change is blocked while an admin reset-PIN or reset-IP recovery flow is pending. PIN-change activity is recorded in `security.log` using the same HMAC IP fingerprint policy as the rest of CdrAuth.

## v0.3.0 Account Management

### Admin commands

```text
/cdrauth status <player|uuid>
/cdrauth resetip <player|uuid>
/cdrauth resetpin <player|uuid>
/cdrauth unreg <player|uuid>
```

Permission: `cdrauth.admin`

### resetip

`/cdrauth resetip <player|uuid>` clears only the trusted-IP binding. The existing PIN remains valid.

On the next authentication, the player enters the old PIN and the current connection IP becomes the new trusted IP. If the player is online when the command is executed, CdrAuth immediately locks the session and opens the login GUI so the rebind can be completed without a relog.

### resetpin

`/cdrauth resetpin <player|uuid>` preserves the trusted IP and marks the account as requiring a new PIN.

The new PIN is created through the normal Java/Bedrock PIN GUI. For security, PIN reset can only be completed from the account's existing trusted IP. Admins never see or set the player's PIN directly.

CdrAuth prevents `resetip` while a PIN reset is pending because removing the trusted IP would make the PIN-reset verification path unavailable. Likewise, `resetpin` is rejected while the account has no trusted IP.

### Detailed status

`/cdrauth status <player|uuid>` reports:

- username and UUID
- online/offline state
- trusted IP state (`BOUND` or `RESET_PENDING`)
- PIN state (`ACTIVE` or `RESET_REQUIRED`)
- current authentication session stage for online players
- account creation timestamp

## v0.2.0 Security Hardening

- Trusted IP auto-login remains enabled: a registered account joining from its bound IP skips the PIN screen.
- New/untrusted IPs must enter the correct PIN and are authorized for that session only.
- Authentication timeout kicks players who do not finish register/login/change-PIN within the configured time.
- Wrong PIN attempts receive a progressive cooldown before another PIN can be submitted.
- Per-session wrong-PIN limits still kick the player after too many failures.
- A second brute-force layer tracks failures by account + HMAC IP fingerprint across reconnects in memory.
- Reconnecting does not reset the brute-force window.
- Reaching the rolling failure threshold temporarily locks that account + IP combination.
- Security events are appended to `plugins/CdrAuth/security.log`.
- Audit logs contain only short HMAC IP fingerprints, never plaintext IP addresses.

## Core account behavior

- First join: register a numeric PIN through GUI and confirm it.
- The registration IP becomes the account's permanent trusted IP.
- Future joins from the same trusted IP are logged in automatically without asking for the PIN again.
- A join from a different IP must enter the correct PIN.
- A different IP is authorized for that session only and does not replace the trusted IP.
- PINs are never stored in plaintext: PBKDF2-HMAC-SHA256 with per-account salt is used.
- Trusted IPs are stored as HMAC-SHA256 fingerprints using a locally generated secret rather than plaintext addresses.
- Before authentication or while a sensitive PIN workflow is active, movement, chat, commands, inventory, interactions, item pickup/drop, and damage are blocked.

## Default security values

```yaml
security:
  max-attempts: 5
  authentication-timeout-seconds: 60
  audit-log-enabled: true
  change-pin:
    require-trusted-ip: true
  cooldown:
    base-seconds: 2
    max-seconds: 15
  bruteforce:
    window-seconds: 600
    max-failures: 10
    lockout-seconds: 300
```

The brute-force state is memory-only in v0.5.0. A full server restart clears temporary failure windows and lockouts; persistent security history remains available in `security.log`.

## Crossplay UI

- Java Edition: inventory-style numeric keypad.
- Bedrock Edition with Floodgate: native Bedrock form keypad.
- Register, login, reset-PIN, reset-IP rebind, and player change-PIN flows use the same crossplay keypad model.
- Admin GUI uses the native Bukkit inventory interface and is intended for Java/Paper server administration.
- If Floodgate is unavailable, CdrAuth continues to work for Java players.

## Platform

- Paper 1.21.11
- Java 21
- Floodgate 2.2.5-SNAPSHOT (optional / provided)

## Important IP note

If the server is behind Velocity, BungeeCord, a TCP proxy, or another reverse proxy, configure proper player IP forwarding first. Otherwise CdrAuth may see the proxy address rather than the player's actual address, which makes trusted-IP logic unreliable.

With `security.unique-ip-ownership: true`, players sharing one public IP (for example the same home Wi-Fi or CGNAT exit) cannot each register separate trusted accounts on that IP. Disable that option if shared public IPs need to be supported.

## Build

```bash
mvn clean package
```

The built JAR is written to `target/CdrAuth-<version>.jar`.

## License

MENKIESTES SOFTWARE LICENSE v1.0. MENKIESTES is created by CADERA.
