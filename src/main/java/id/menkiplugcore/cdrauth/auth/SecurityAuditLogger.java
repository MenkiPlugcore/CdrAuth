package id.menkiplugcore.cdrauth.auth;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

final class SecurityAuditLogger {
    private final CdrAuthPlugin plugin;
    private final Path file;

    SecurityAuditLogger(CdrAuthPlugin plugin) throws IOException {
        this.plugin = plugin;
        if (!Files.exists(plugin.getDataFolder().toPath())) {
            Files.createDirectories(plugin.getDataFolder().toPath());
        }
        this.file = plugin.getDataFolder().toPath().resolve("security.log");
        if (!Files.exists(file)) {
            Files.createFile(file);
        }
    }

    synchronized void log(String event, String playerName, String uuid, String ipFingerprint, String detail) {
        if (!plugin.getConfig().getBoolean("security.audit-log-enabled", true)) {
            return;
        }

        String safeDetail = detail == null ? "" : detail.replace('\n', ' ').replace('\r', ' ');
        String line = "%s | %s | player=%s | uuid=%s | ip=%s | %s%n".formatted(
                Instant.now(), event, playerName, uuid, ipFingerprint, safeDetail
        );

        try {
            Files.writeString(
                    file,
                    line,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not write CdrAuth security.log: " + exception.getMessage());
        }
    }
}
