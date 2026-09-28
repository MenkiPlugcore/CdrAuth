package id.menkiplugcore.cdrauth.auth;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

        try {
            rotateIfNeeded();
            String safeDetail = sanitize(detail);
            String line = "%s | %s | player=%s | uuid=%s | ip=%s | %s%n".formatted(
                    Instant.now(), event, playerName, uuid, ipFingerprint, safeDetail
            );
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

    synchronized List<AuditEntry> recent(UUID playerFilter, String eventFilter, int requestedLimit) {
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        int scanLimit = Math.max(limit, plugin.getConfig().getInt("security.audit.viewer-scan-lines", 5000));
        List<AuditEntry> result = new ArrayList<>();
        int scanned = 0;

        for (Path source : logFilesNewestFirst()) {
            if (!Files.exists(source)) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0 && result.size() < limit && scanned < scanLimit; i--) {
                    scanned++;
                    AuditEntry entry = parse(lines.get(i));
                    if (entry == null) {
                        continue;
                    }
                    if (playerFilter != null && !entry.belongsTo(playerFilter)) {
                        continue;
                    }
                    if (!entry.matchesEvent(eventFilter)) {
                        continue;
                    }
                    result.add(entry);
                }
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not read CdrAuth audit history: " + exception.getMessage());
            }
            if (result.size() >= limit || scanned >= scanLimit) {
                break;
            }
        }
        return result;
    }

    synchronized List<String> recentEventTypes(UUID playerFilter, int requestedLimit) {
        int maxTypes = Math.max(1, Math.min(requestedLimit, 100));
        Set<String> types = new LinkedHashSet<>();
        for (AuditEntry entry : recent(playerFilter, "ALL", Math.max(250, maxTypes * 20))) {
            types.add(entry.event());
            if (types.size() >= maxTypes) {
                break;
            }
        }
        return new ArrayList<>(types);
    }

    private void rotateIfNeeded() throws IOException {
        long maxKilobytes = Math.max(64L, plugin.getConfig().getLong("security.audit.max-file-size-kb", 1024L));
        long maxBytes = maxKilobytes * 1024L;
        if (!Files.exists(file) || Files.size(file) < maxBytes) {
            return;
        }

        int historyFiles = Math.max(1, Math.min(20, plugin.getConfig().getInt("security.audit.history-files", 3)));
        Path oldest = rotated(historyFiles);
        Files.deleteIfExists(oldest);
        for (int index = historyFiles - 1; index >= 1; index--) {
            Path source = rotated(index);
            if (Files.exists(source)) {
                Files.move(source, rotated(index + 1), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.move(file, rotated(1), StandardCopyOption.REPLACE_EXISTING);
        Files.createFile(file);
    }

    private List<Path> logFilesNewestFirst() {
        List<Path> paths = new ArrayList<>();
        paths.add(file);
        int historyFiles = Math.max(1, Math.min(20, plugin.getConfig().getInt("security.audit.history-files", 3)));
        for (int index = 1; index <= historyFiles; index++) {
            paths.add(rotated(index));
        }
        return paths;
    }

    private Path rotated(int index) {
        return plugin.getDataFolder().toPath().resolve("security.log." + index);
    }

    private AuditEntry parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] parts = line.split(" \\| ", 6);
        if (parts.length < 5) {
            return null;
        }
        try {
            Instant timestamp = Instant.parse(parts[0].trim());
            String event = parts[1].trim();
            String player = value(parts[2], "player=");
            String uuidRaw = value(parts[3], "uuid=");
            String ip = value(parts[4], "ip=");
            String detail = parts.length >= 6 ? parts[5].trim() : "";
            UUID uuid = UUID.fromString(uuidRaw);
            return new AuditEntry(timestamp, event, player, uuid, ip, detail);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String value(String part, String prefix) {
        String trimmed = part.trim();
        return trimmed.startsWith(prefix) ? trimmed.substring(prefix.length()) : trimmed;
    }

    private String sanitize(String detail) {
        if (detail == null) {
            return "";
        }
        return detail
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('|', '/');
    }
}
