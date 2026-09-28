package id.menkiplugcore.cdrauth.auth;

public enum AuthStage {
    REGISTER,
    CONFIRM_REGISTER,
    LOGIN,
    RESET_PIN,
    CONFIRM_RESET_PIN,
    CHANGE_PIN_VERIFY,
    CHANGE_PIN_NEW,
    CONFIRM_CHANGE_PIN,
    AUTHENTICATED
}
