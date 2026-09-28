# CdrAuth

Crossplay PIN authentication plugin for Paper servers.

CdrAuth provides a GUI-based register/login flow inspired by AuthMe, with strict one-IP-per-account binding. Java players use an inventory PIN keypad; Bedrock players can use a native Floodgate form when Floodgate is available.

## Planned v0.1.0 core

- Register PIN through GUI (no PIN typed in chat)
- Login PIN through GUI
- Java inventory keypad
- Bedrock native Floodgate form with automatic Java-GUI fallback
- One registered IP per account
- Admin-only unregister before an account can bind to another IP
- PBKDF2-HMAC-SHA256 PIN hashing with per-account salt
- HMAC-SHA256 IP fingerprinting with a locally generated secret
- Movement, interaction, command, chat, damage, pickup, and inventory protection before authentication
- Configurable PIN length and failed-attempt limit

## Platform

- Paper 1.21.11
- Java 21
- Floodgate 2.2.5-SNAPSHOT (optional / provided)

## Important IP note

If the server is behind Velocity, BungeeCord, a TCP proxy, or another reverse proxy, configure proper player IP forwarding first. Otherwise CdrAuth can see the proxy address instead of the player's real address, which defeats strict per-account IP binding.

Strict IP binding can also require admin intervention when a legitimate player's ISP/mobile network changes their public IP. This is intentional for the requested security model: the account must be unregistered by an admin before it can bind to a new IP.

## Admin command

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
