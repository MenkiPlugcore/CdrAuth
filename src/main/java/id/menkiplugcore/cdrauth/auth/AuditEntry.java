package id.menkiplugcore.cdrauth.auth;

import java.time.Instant;
import java.util.UUID;

public record AuditEntry(
        Instant timestamp,
        String event,
        String playerName,
        UUID uuid,
        String ipFingerprint,
        String detail
) {
    public boolean belongsTo(UUID target) {
        return uuid != null && uuid.equals(target);
    }

    public boolean matchesEvent(String filter) {
        if (filter == null || filter.isBlank() || filter.equalsIgnoreCase("ALL")) {
            return true;
        }
        return event.equalsIgnoreCase(filter);
    }
}
