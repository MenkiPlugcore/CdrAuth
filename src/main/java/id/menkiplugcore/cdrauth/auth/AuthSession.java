package id.menkiplugcore.cdrauth.auth;

final class AuthSession {
    private AuthStage stage;
    private String firstPin;
    private int attempts;
    private long cooldownUntil;

    AuthSession(AuthStage stage) {
        this.stage = stage;
    }

    AuthStage stage() {
        return stage;
    }

    void stage(AuthStage stage) {
        this.stage = stage;
    }

    String firstPin() {
        return firstPin;
    }

    void firstPin(String firstPin) {
        this.firstPin = firstPin;
    }

    int incrementAttempts() {
        return ++attempts;
    }

    int attempts() {
        return attempts;
    }

    void applyCooldown(long milliseconds) {
        cooldownUntil = Math.max(cooldownUntil, System.currentTimeMillis() + Math.max(0L, milliseconds));
    }

    long remainingCooldownMillis() {
        return Math.max(0L, cooldownUntil - System.currentTimeMillis());
    }
}
