package id.menkiplugcore.cdrauth.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class IpHasher {
    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    public IpHasher(Path secretFile) throws IOException {
        Files.createDirectories(secretFile.getParent());
        if (Files.exists(secretFile)) {
            this.secret = Base64.getDecoder().decode(Files.readString(secretFile, StandardCharsets.UTF_8).trim());
        } else {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            Files.writeString(secretFile, Base64.getEncoder().encodeToString(generated), StandardCharsets.UTF_8);
            this.secret = generated;
        }
    }

    public String hash(String ipAddress) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(ipAddress.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    public boolean matches(String ipAddress, String expectedHmac) {
        try {
            byte[] expected = Base64.getDecoder().decode(expectedHmac);
            byte[] actual = Base64.getDecoder().decode(hash(ipAddress));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
