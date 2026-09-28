# CdrAuth

Crossplay PIN authentication plugin for Paper servers.

CdrAuth provides a GUI-based register/login flow inspired by AuthMe. Java players use an inventory PIN keypad; Bedrock players use a native Floodgate form when Floodgate is available.

## v0.2.0 Security Hardening

- Trusted IP auto-login remains enabled: a registered account joining from its bound IP skips the PIN screen.
- New/untrusted IPs must enter the correct PIN and are authorized for that session only.
- Authentication timeout kicks players who do not finish register/login within the configured time.
- Wrong PIN attempts receive a progressive cooldown before another PIN can be submitted.
- Per-session wrong-PIN limits still kick the player after too many failures.
- A second brute-force layer tracks failures by account + HMAC IP fingerprint across reconnects in memory.
- Reconnecting no longer resets the brute-force window.
- Reaching the rolling failure threshold temporarily locks that account + IP combination.
- Security events are appended to `plugins/CdrAuth/security.log`.
- Audit logs contain only short HMAC IP fingerprints, never plaintext IP addresses.
- Events include auto login, new-IP challenge, successful/failed PIN attempts, account collisions, brute-force lockouts, and authentication timeouts.

## Core account behavior

- First join: register a numeric PIN through GUI and confirm it.
- The registration IP becomes the account's permanent **trusted IP**.
- Future joins from the same trusted IP are logged in automatically without asking for the PIN again.
- A join from a different IP must enter the correct PIN.
- A different IP is authorized for that session only and does not replace the trusted IP.
- Moving the permanent trusted IP requires an admin to `/cdrauth unreg <player|uuid>` first, followed by registration again from the new IP.
- Optional strict IP ownership prevents one trusted IP from being registered to multiple accounts and shows an account-collision notice when a conflict is detected.
- PINs are never stored in plaintext: PBKDF2-HMAC-SHA256 with per-account salt is used.
- Trusted IPs are stored as HMAC-SHA256 fingerprints using a locally generated secret rather than plaintext addresses.
- Before authentication, movement, chat, commands, inventory, interactions, item pickup/drop, and damage are blocked.

## Default security values

```yaml
security:
  max-attempts: 5
  authentication-timeout-seconds: 60
  audit-log-enabled: true
  cooldown:
    base-seconds: 2
    max-seconds: 15
  bruteforce:
    window-seconds: 600
    max-failures: 10
    lockout-seconds: 300
```

The brute-force state is intentionally memory-only in v0.2.0. A full server restart clears temporary failure windows and lockouts; persistent security history remains available in `security.log`.

## Crossplay UI

- Java Edition: inventory-style numeric keypad.
- Bedrock Edition with Floodgate: native Bedrock form keypad.
- If Floodgate is unavailable, CdrAuth continues to work for Java players.

## Platform

- Paper 1.21.11
- Java 21
- Floodgate 2.2.5-SNAPSHOT (optional / provided)

## Important IP note

If the server is behind Velocity, BungeeCord, a TCP proxy, or another reverse proxy, configure proper player IP forwarding first. Otherwise CdrAuth may see the proxy address rather than the player's actual address, which makes trusted-IP logic unreliable.

With `security.unique-ip-ownership: true`, players sharing one public IP (for example the same home Wi-Fi or CGNAT exit) cannot each register separate trusted accounts on that IP. Disable that option if shared public IPs need to be supported.

## Admin commands

```text
/cdrauth unreg <player|uuid>
/cdrauth status <player|uuid>
```

Permission: `cdrauth.admin`

## Build

```bash
mvn clean package
```

The built JAR is written to `target/CdrAuth-<version>.jar`.

## License

MENKIESTES SOFTWARE LICENSE v1.0. MENKIESTES is created by CADERA.
