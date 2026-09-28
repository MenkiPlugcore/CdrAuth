package id.menkiplugcore.cdrauth.storage;

import java.util.UUID;

public record AccountRecord(
        UUID uuid,
        String username,
        String pinSalt,
        String pinHash,
        String ipHmac,
        long createdAt,
        boolean pinResetRequired
) {
    public boolean hasTrustedIp() {
        return ipHmac != null && !ipHmac.isBlank();
    }
}
