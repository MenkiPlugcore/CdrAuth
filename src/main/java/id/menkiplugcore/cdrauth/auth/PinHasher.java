package id.menkiplugcore.cdrauth.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PinHasher {
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom secureRandom = new SecureRandom();
    private final int iterations;

    public PinHasher(int iterations) {
        this.iterations = iterations;
    }

    public Hash hash(String pin) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] derived = derive(pin, salt);
        return new Hash(Base64.getEncoder().encodeToString(salt), Base64.getEncoder().encodeToString(derived));
    }

    public boolean verify(String pin, String saltBase64, String hashBase64) {
        try {
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            byte[] expected = Base64.getDecoder().decode(hashBase64);
            byte[] actual = derive(pin, salt);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private byte[] derive(String pin, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 is unavailable", exception);
        } finally {
            spec.clearPassword();
        }
    }

    public record Hash(String salt, String hash) {
    }
}
