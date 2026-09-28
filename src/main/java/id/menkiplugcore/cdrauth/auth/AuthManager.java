package id.menkiplugcore.cdrauth.auth;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;
import id.menkiplugcore.cdrauth.storage.AccountRecord;
import id.menkiplugcore.cdrauth.storage.AccountStore;
import org.bukkit.entity.Player;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthManager {
    private final CdrAuthPlugin plugin;
    private final AccountStore store;
    private final PinHasher pinHasher;
    private final IpHasher ipHasher;
    private final SecurityAuditLogger auditLogger;
    private final Map<UUID, AuthSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, FailureState> failureStates = new ConcurrentHashMap<>();

    public AuthManager(CdrAuthPlugin plugin, AccountStore store) throws Exception {
        this.plugin = plugin;
        this.store = store;
        int iterations = Math.max(100_000, plugin.getConfig().getInt("security.pbkdf2-iterations", 210_000));
        this.pinHasher = new PinHasher(iterations);
        Path secretFile = plugin.getDataFolder().toPath().resolve("ip-secret.key");
        this.ipHasher = new IpHasher(secretFile);
        this.auditLogger = new SecurityAuditLogger(plugin);
    }

    public boolean begin(Player player) {
        sessions.remove(player.getUniqueId());
        Optional<AccountRecord> account = store.find(player.getUniqueId());
        String ip = currentIp(player);

        if (ip == null) {
            player.kick(plugin.component(plugin.msg("messages.no-address")));
            return false;
        }

        String currentIpHmac = ipHasher.hash(ip);
        String ipFingerprint = shortFingerprint(currentIpHmac);
        if (hasIpCollision(player.getUniqueId(), currentIpHmac)) {
            AccountRecord owner = store.findByIpHmac(currentIpHmac).orElseThrow();
            audit("ACCOUNT_COLLISION", player, ipFingerprint, "boundTo=" + owner.username());
            player.kick(plugin.component(plugin.msg(
                    "messages.account-collision",
                    "%player%", owner.username()
            )));
            return false;
        }

        if (account.isPresent()) {
            AccountRecord record = account.get();

            if (record.pinResetRequired()) {
                if (!record.hasTrustedIp()) {
                    audit("PIN_RESET_BLOCKED", player, ipFingerprint, "reason=noTrustedIp");
                    player.kick(plugin.component(plugin.msg("messages.pin-reset-no-trusted-ip")));
                    return false;
                }
                if (!ipHasher.matches(ip, record.ipHmac())) {
                    audit("PIN_RESET_BLOCKED", player, ipFingerprint, "reason=untrustedIp");
                    player.kick(plugin.component(plugin.msg("messages.pin-reset-untrusted-ip")));
                    return false;
                }

                AuthSession session = new AuthSession(AuthStage.RESET_PIN);
                sessions.put(player.getUniqueId(), session);
                scheduleTimeout(player, session, ipFingerprint);
                audit("PIN_RESET_CHALLENGE", player, ipFingerprint, "trustedIp=true");
                player.sendMessage(plugin.prefix() + plugin.msg("messages.pin-reset-start", "%length%", Integer.toString(pinLength())));
                return true;
            }

            boolean trustedIp = record.hasTrustedIp() && ipHasher.matches(ip, record.ipHmac());
            boolean trustedIpAutoLogin = plugin.getConfig().getBoolean("security.trusted-ip-auto-login", true);

            if (trustedIp && trustedIpAutoLogin) {
                sessions.put(player.getUniqueId(), new AuthSession(AuthStage.AUTHENTICATED));
                store.updateUsername(player.getUniqueId(), player.getName());
                clearFailureState(player.getUniqueId(), currentIpHmac);
                audit("AUTO_LOGIN", player, ipFingerprint, "trustedIp=true");
                player.sendMessage(plugin.prefix() + plugin.msg("messages.auto-login"));
                return true;
            }

            long lockSeconds = remainingLockSeconds(player.getUniqueId(), currentIpHmac);
            if (lockSeconds > 0) {
                audit("LOCKED_LOGIN_BLOCK", player, ipFingerprint, "remainingSeconds=" + lockSeconds);
                player.kick(plugin.component(plugin.msg(
                        "messages.bruteforce-locked",
                        "%seconds%", Long.toString(lockSeconds)
                )));
                return false;
            }

            AuthSession session = new AuthSession(AuthStage.LOGIN);
            sessions.put(player.getUniqueId(), session);
            scheduleTimeout(player, session, ipFingerprint);
            if (!record.hasTrustedIp()) {
                audit("IP_REBIND_CHALLENGE", player, ipFingerprint, "trustedIpPending=true");
                player.sendMessage(plugin.prefix() + plugin.msg("messages.ip-rebind-login-start"));
            } else if (trustedIp) {
                audit("LOGIN_CHALLENGE", player, ipFingerprint, "trustedIp=true autoLogin=false");
                player.sendMessage(plugin.prefix() + plugin.msg("messages.login-start"));
            } else {
                audit("NEW_IP_CHALLENGE", player, ipFingerprint, "trustedIp=false");
                player.sendMessage(plugin.prefix() + plugin.msg("messages.new-ip-login-start"));
            }
        } else {
            AuthSession session = new AuthSession(AuthStage.REGISTER);
            sessions.put(player.getUniqueId(), session);
            scheduleTimeout(player, session, ipFingerprint);
            audit("REGISTER_CHALLENGE", player, ipFingerprint, "newAccount=true");
            player.sendMessage(plugin.prefix() + plugin.msg("messages.register-start", "%length%", Integer.toString(pinLength())));
        }
        return true;
    }

    public AuthResult submitPin(Player player, String pin) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session == null || session.stage() == AuthStage.AUTHENTICATED) {
            return AuthResult.success("");
        }

        long cooldownMillis = session.remainingCooldownMillis();
        if (cooldownMillis > 0) {
            long cooldownSeconds = Math.max(1L, (cooldownMillis + 999L) / 1000L);
            return AuthResult.retry(plugin.msg(
                    "messages.cooldown-active",
                    "%seconds%", Long.toString(cooldownSeconds)
            ));
        }

        if (!pin.matches("\\d{" + pinLength() + "}")) {
            return AuthResult.retry(plugin.msg("messages.pin-invalid", "%length%", Integer.toString(pinLength())));
        }

        return switch (session.stage()) {
            case REGISTER -> {
                session.firstPin(pin);
                session.stage(AuthStage.CONFIRM_REGISTER);
                yield AuthResult.next(plugin.msg("messages.confirm-pin"));
            }
            case CONFIRM_REGISTER -> finishRegistration(player, session, pin);
            case LOGIN -> finishLogin(player, session, pin);
            case RESET_PIN -> {
                session.firstPin(pin);
                session.stage(AuthStage.CONFIRM_RESET_PIN);
                yield AuthResult.next(plugin.msg("messages.confirm-new-pin"));
            }
            case CONFIRM_RESET_PIN -> finishPinReset(player, session, pin);
            case AUTHENTICATED -> AuthResult.success("");
        };
    }

    private AuthResult finishRegistration(Player player, AuthSession session, String pin) {
        byte[] first = session.firstPin() == null ? new byte[0] : session.firstPin().getBytes(StandardCharsets.UTF_8);
        byte[] second = pin.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(first, second)) {
            session.firstPin(null);
            session.stage(AuthStage.REGISTER);
            session.applyCooldown(progressiveCooldownMillis(1));
            audit("REGISTER_PIN_MISMATCH", player, fingerprint(player), "confirmationMismatch=true");
            return AuthResult.retry(plugin.msg("messages.pin-mismatch"));
        }

        String ip = currentIp(player);
        if (ip == null) {
            return AuthResult.kick(plugin.msg("messages.no-address"));
        }

        String currentIpHmac = ipHasher.hash(ip);
        String ipFingerprint = shortFingerprint(currentIpHmac);
        if (hasIpCollision(player.getUniqueId(), currentIpHmac)) {
            AccountRecord owner = store.findByIpHmac(currentIpHmac).orElseThrow();
            audit("ACCOUNT_COLLISION", player, ipFingerprint, "boundTo=" + owner.username());
            return AuthResult.kick(plugin.msg(
                    "messages.account-collision",
                    "%player%", owner.username()
            ));
        }

        PinHasher.Hash hashed = pinHasher.hash(pin);
        AccountRecord record = new AccountRecord(
                player.getUniqueId(),
                player.getName(),
                hashed.salt(),
                hashed.hash(),
                currentIpHmac,
                System.currentTimeMillis(),
                false
        );
        store.register(record);
        clearFailureState(player.getUniqueId(), currentIpHmac);
        session.firstPin(null);
        session.stage(AuthStage.AUTHENTICATED);
        audit("REGISTER_SUCCESS", player, ipFingerprint, "trustedIpBound=true");
        return AuthResult.success(plugin.msg("messages.registered"));
    }

    private AuthResult finishLogin(Player player, AuthSession session, String pin) {
        Optional<AccountRecord> account = store.find(player.getUniqueId());
        if (account.isEmpty()) {
            session.stage(AuthStage.REGISTER);
            return AuthResult.next(plugin.msg("messages.register-start", "%length%", Integer.toString(pinLength())));
        }

        String ip = currentIp(player);
        if (ip == null) {
            return AuthResult.kick(plugin.msg("messages.no-address"));
        }

        String currentIpHmac = ipHasher.hash(ip);
        String ipFingerprint = shortFingerprint(currentIpHmac);
        long activeLockSeconds = remainingLockSeconds(player.getUniqueId(), currentIpHmac);
        if (activeLockSeconds > 0) {
            audit("LOCKED_LOGIN_BLOCK", player, ipFingerprint, "remainingSeconds=" + activeLockSeconds);
            return AuthResult.kick(plugin.msg(
                    "messages.bruteforce-locked",
                    "%seconds%", Long.toString(activeLockSeconds)
            ));
        }

        AccountRecord record = account.get();
        if (pinHasher.verify(pin, record.pinSalt(), record.pinHash())) {
            if (!record.hasTrustedIp()) {
                if (hasIpCollision(player.getUniqueId(), currentIpHmac)) {
                    AccountRecord owner = store.findByIpHmac(currentIpHmac).orElseThrow();
                    audit("ACCOUNT_COLLISION", player, ipFingerprint, "boundTo=" + owner.username());
                    return AuthResult.kick(plugin.msg(
                            "messages.account-collision",
                            "%player%", owner.username()
                    ));
                }
                store.updateTrustedIp(player.getUniqueId(), currentIpHmac);
                session.stage(AuthStage.AUTHENTICATED);
                store.updateUsername(player.getUniqueId(), player.getName());
                clearFailureState(player.getUniqueId(), currentIpHmac);
                audit("IP_REBIND_SUCCESS", player, ipFingerprint, "trustedIpBound=true");
                return AuthResult.success(plugin.msg("messages.ip-rebind-success"));
            }

            session.stage(AuthStage.AUTHENTICATED);
            store.updateUsername(player.getUniqueId(), player.getName());
            clearFailureState(player.getUniqueId(), currentIpHmac);

            boolean trustedIp = ipHasher.matches(ip, record.ipHmac());
            if (!trustedIp) {
                audit("NEW_IP_LOGIN_SUCCESS", player, ipFingerprint, "sessionOnly=true");
                return AuthResult.success(plugin.msg("messages.logged-in-new-ip"));
            }
            audit("LOGIN_SUCCESS", player, ipFingerprint, "trustedIp=true");
            return AuthResult.success(plugin.msg("messages.logged-in"));
        }

        int attempts = session.incrementAttempts();
        int maxAttempts = Math.max(1, plugin.getConfig().getInt("security.max-attempts", 5));
        long globalLockSeconds = registerFailure(player.getUniqueId(), currentIpHmac);
        audit("WRONG_PIN", player, ipFingerprint, "sessionAttempt=" + attempts + "/" + maxAttempts);

        if (globalLockSeconds > 0) {
            audit("BRUTE_FORCE_LOCK", player, ipFingerprint, "lockSeconds=" + globalLockSeconds);
            return AuthResult.kick(plugin.msg(
                    "messages.bruteforce-locked",
                    "%seconds%", Long.toString(globalLockSeconds)
            ));
        }

        if (attempts >= maxAttempts) {
            audit("SESSION_ATTEMPT_LIMIT", player, ipFingerprint, "attempts=" + attempts);
            return AuthResult.kick(plugin.msg("messages.too-many-attempts"));
        }

        long cooldown = progressiveCooldownMillis(attempts);
        session.applyCooldown(cooldown);
        long cooldownSeconds = Math.max(1L, (cooldown + 999L) / 1000L);
        return AuthResult.retry(plugin.msg(
                "messages.wrong-pin-cooldown",
                "%remaining%", Integer.toString(maxAttempts - attempts),
                "%seconds%", Long.toString(cooldownSeconds)
        ));
    }

    private AuthResult finishPinReset(Player player, AuthSession session, String pin) {
        byte[] first = session.firstPin() == null ? new byte[0] : session.firstPin().getBytes(StandardCharsets.UTF_8);
        byte[] second = pin.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(first, second)) {
            session.firstPin(null);
            session.stage(AuthStage.RESET_PIN);
            session.applyCooldown(progressiveCooldownMillis(1));
            audit("PIN_RESET_MISMATCH", player, fingerprint(player), "confirmationMismatch=true");
            return AuthResult.retry(plugin.msg("messages.pin-mismatch"));
        }

        PinHasher.Hash hashed = pinHasher.hash(pin);
        store.updatePin(player.getUniqueId(), hashed.salt(), hashed.hash());
        session.firstPin(null);
        session.stage(AuthStage.AUTHENTICATED);
        audit("PIN_RESET_SUCCESS", player, fingerprint(player), "pinReplaced=true");
        return AuthResult.success(plugin.msg("messages.pin-reset-success"));
    }

    public void prepareRegistration(Player player) {
        AuthSession session = new AuthSession(AuthStage.REGISTER);
        sessions.put(player.getUniqueId(), session);
        scheduleTimeout(player, session, fingerprint(player));
    }

    public boolean prepareIpRebind(Player player) {
        if (currentIp(player) == null) {
            player.kick(plugin.component(plugin.msg("messages.no-address")));
            return false;
        }
        AuthSession session = new AuthSession(AuthStage.LOGIN);
        sessions.put(player.getUniqueId(), session);
        scheduleTimeout(player, session, fingerprint(player));
        player.sendMessage(plugin.prefix() + plugin.msg("messages.ip-rebind-login-start"));
        return true;
    }

    public boolean preparePinReset(Player player) {
        Optional<AccountRecord> account = store.find(player.getUniqueId());
        String ip = currentIp(player);
        if (account.isEmpty() || ip == null) {
            return false;
        }

        AccountRecord record = account.get();
        if (!record.hasTrustedIp()) {
            player.kick(plugin.component(plugin.msg("messages.pin-reset-no-trusted-ip")));
            return false;
        }
        if (!ipHasher.matches(ip, record.ipHmac())) {
            player.kick(plugin.component(plugin.msg("messages.pin-reset-untrusted-ip")));
            return false;
        }

        AuthSession session = new AuthSession(AuthStage.RESET_PIN);
        sessions.put(player.getUniqueId(), session);
        scheduleTimeout(player, session, fingerprint(player));
        player.sendMessage(plugin.prefix() + plugin.msg("messages.pin-reset-start", "%length%", Integer.toString(pinLength())));
        return true;
    }

    public boolean needsAuthentication(UUID uuid) {
        AuthSession session = sessions.get(uuid);
        return session == null || session.stage() != AuthStage.AUTHENTICATED;
    }

    public AuthStage stage(UUID uuid) {
        AuthSession session = sessions.get(uuid);
        return session == null ? AuthStage.LOGIN : session.stage();
    }

    public int pinLength() {
        return Math.max(4, Math.min(12, plugin.getConfig().getInt("security.pin-length", 6)));
    }

    public void endSession(UUID uuid) {
        sessions.remove(uuid);
    }

    public void clearSessions() {
        sessions.clear();
        failureStates.clear();
    }

    private void scheduleTimeout(Player player, AuthSession session, String ipFingerprint) {
        int timeoutSeconds = Math.max(15, plugin.getConfig().getInt("security.authentication-timeout-seconds", 60));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            AuthSession active = sessions.get(player.getUniqueId());
            if (!player.isOnline() || active != session || active.stage() == AuthStage.AUTHENTICATED) {
                return;
            }
            audit("AUTH_TIMEOUT", player, ipFingerprint, "timeoutSeconds=" + timeoutSeconds);
            sessions.remove(player.getUniqueId(), session);
            player.kick(plugin.component(plugin.msg(
                    "messages.authentication-timeout",
                    "%seconds%", Integer.toString(timeoutSeconds)
            )));
        }, timeoutSeconds * 20L);
    }

    private boolean hasIpCollision(UUID uuid, String ipHmac) {
        if (!plugin.getConfig().getBoolean("security.unique-ip-ownership", true)) {
            return false;
        }
        Optional<AccountRecord> owner = store.findByIpHmac(ipHmac);
        return owner.isPresent() && !owner.get().uuid().equals(uuid);
    }

    private long progressiveCooldownMillis(int attempt) {
        long baseSeconds = Math.max(1L, plugin.getConfig().getLong("security.cooldown.base-seconds", 2L));
        long maxSeconds = Math.max(baseSeconds, plugin.getConfig().getLong("security.cooldown.max-seconds", 15L));
        long multiplier = Math.max(1L, attempt);
        return Math.min(maxSeconds, baseSeconds * multiplier) * 1000L;
    }

    private long registerFailure(UUID uuid, String ipHmac) {
        String key = failureKey(uuid, ipHmac);
        FailureState state = failureStates.computeIfAbsent(key, ignored -> new FailureState());
        long now = System.currentTimeMillis();
        long windowMillis = Math.max(60L, plugin.getConfig().getLong("security.bruteforce.window-seconds", 600L)) * 1000L;
        int maxFailures = Math.max(2, plugin.getConfig().getInt("security.bruteforce.max-failures", 10));
        long lockMillis = Math.max(30L, plugin.getConfig().getLong("security.bruteforce.lockout-seconds", 300L)) * 1000L;

        synchronized (state) {
            if (state.windowStartedAt == 0L || now - state.windowStartedAt > windowMillis) {
                state.windowStartedAt = now;
                state.failures = 0;
                state.lockedUntil = 0L;
            }

            state.failures++;
            if (state.failures >= maxFailures) {
                state.lockedUntil = now + lockMillis;
                return Math.max(1L, lockMillis / 1000L);
            }
        }
        return 0L;
    }

    private long remainingLockSeconds(UUID uuid, String ipHmac) {
        FailureState state = failureStates.get(failureKey(uuid, ipHmac));
        if (state == null) {
            return 0L;
        }

        long now = System.currentTimeMillis();
        synchronized (state) {
            if (state.lockedUntil <= now) {
                if (state.lockedUntil > 0L) {
                    failureStates.remove(failureKey(uuid, ipHmac), state);
                }
                return 0L;
            }
            return Math.max(1L, (state.lockedUntil - now + 999L) / 1000L);
        }
    }

    private void clearFailureState(UUID uuid, String ipHmac) {
        failureStates.remove(failureKey(uuid, ipHmac));
    }

    private String failureKey(UUID uuid, String ipHmac) {
        return uuid + ":" + ipHmac;
    }

    private void audit(String event, Player player, String ipFingerprint, String detail) {
        auditLogger.log(event, player.getName(), player.getUniqueId().toString(), ipFingerprint, detail);
    }

    private String fingerprint(Player player) {
        String ip = currentIp(player);
        return ip == null ? "unknown" : shortFingerprint(ipHasher.hash(ip));
    }

    private String shortFingerprint(String hmac) {
        return hmac.length() <= 12 ? hmac : hmac.substring(0, 12);
    }

    private String currentIp(Player player) {
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) {
            return null;
        }
        return address.getAddress().getHostAddress();
    }

    private static final class FailureState {
        private int failures;
        private long windowStartedAt;
        private long lockedUntil;
    }
}
