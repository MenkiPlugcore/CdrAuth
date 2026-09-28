# CdrAuth

Crossplay PIN authentication plugin for Paper servers.

CdrAuth provides a GUI-based register/login flow inspired by AuthMe. Java players use an inventory PIN keypad; Bedrock players use a native Floodgate form when Floodgate is available.

## v0.6.0 Audit Log

CdrAuth security history is now queryable from the Admin GUI while continuing to use the same human-readable `plugins/CdrAuth/security.log` file.

Admin shortcuts:

```text
/cdrauth audit
/cdrauth audit <player|uuid>
```

`/cdrauth audit` opens global security history. Supplying a player or UUID filters the viewer to that account.

Audit viewer features:

- Global security-event history from the Admin GUI account list.
- Per-account security history from the account detail screen.
- 45 events per page with previous/next navigation.
- Event-type filter cycling (`ALL`, `WRONG_PIN`, `LOGIN_SUCCESS`, and other event types found in recent history).
- Manual refresh directly from the viewer.
- Timestamp, player, UUID, HMAC IP fingerprint, event name, and event detail.
- No plaintext IP addresses are exposed in the log or GUI.
- Existing v0.2.0 log lines remain readable by the v0.6.0 viewer.

Log retention and viewer limits are configurable:

```yaml
security:
  audit-log-enabled: true
  audit:
    max-file-size-kb: 1024
    history-files: 3
    viewer-max-entries: 500
    viewer-scan-lines: 5000
```

When `security.log` reaches the configured size, CdrAuth rotates it to `security.log.1`, shifts older history files, and keeps the configured number of rotated logs. The viewer reads the active and rotated files from newest to oldest.

## v0.5.0 Admin GUI

Admins can manage registered CdrAuth accounts through an inventory GUI:

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

`resetip` clears only the trusted-IP binding while preserving the existing PIN. The next successful PIN login binds the current IP as the new trusted IP.

`resetpin` preserves the trusted IP and requires the player to create a new PIN from that trusted IP. Admins never see or set the player's PIN directly.

## v0.2.0 Security Hardening

- Trusted IP auto-login remains enabled.
- New/untrusted IPs must enter the correct PIN and are authorized for that session only.
- Authentication timeout protects incomplete auth flows.
- Wrong PIN attempts use progressive cooldown and per-session attempt limits.
- A second brute-force layer tracks failures by account + HMAC IP fingerprint across reconnects in memory.
- Reaching the rolling failure threshold temporarily locks that account + IP combination.
- Security events are written to `plugins/CdrAuth/security.log`.
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

The brute-force state is memory-only in v0.6.0. A full server restart clears temporary failure windows and lockouts; persistent security history remains available in active and rotated `security.log` files.

## Crossplay UI

- Java Edition: inventory-style numeric keypad.
- Bedrock Edition with Floodgate: native Bedrock form keypad.
- Register, login, reset-PIN, reset-IP rebind, and player change-PIN flows use the same crossplay keypad model.
- Admin GUI and audit viewer use the native Bukkit inventory interface.
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
