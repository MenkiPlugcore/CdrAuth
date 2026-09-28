package id.menkiplugcore.cdrauth.auth;

final class AuthSession {
    private AuthStage stage;
    private String firstPin;
    private int attempts;

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
}
