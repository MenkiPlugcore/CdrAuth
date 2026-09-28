package id.menkiplugcore.cdrauth.auth;

public record AuthResult(Type type, String message) {
    public enum Type {
        SUCCESS,
        NEXT,
        RETRY,
        KICK
    }

    public static AuthResult success(String message) {
        return new AuthResult(Type.SUCCESS, message);
    }

    public static AuthResult next(String message) {
        return new AuthResult(Type.NEXT, message);
    }

    public static AuthResult retry(String message) {
        return new AuthResult(Type.RETRY, message);
    }

    public static AuthResult kick(String message) {
        return new AuthResult(Type.KICK, message);
    }
}
