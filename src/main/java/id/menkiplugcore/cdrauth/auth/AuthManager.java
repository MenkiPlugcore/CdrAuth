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
    private final Map<UUID, AuthSession> sessions = new ConcurrentHashMap<>();

    public AuthManager(CdrAuthPlugin plugin, AccountStore store) throws Exception {
        this.plugin = plugin;
        this.store = store;
        int iterations = Math.max(100_000, plugin.getConfig().getInt("security.pbkdf2-iterations", 210_000));
        this.pinHasher = new PinHasher(iterations);
        Path secretFile = plugin.getDataFolder().toPath().resolve("ip-secret.key");
        this.ipHasher = new IpHasher(secretFile);
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
        boolean uniqueIpOwnership = plugin.getConfig().getBoolean("security.unique-ip-ownership", true);
        if (uniqueIpOwnership) {
            Optional<AccountRecord> ipOwner = store.findByIpHmac(currentIpHmac);
            if (ipOwner.isPresent() && !ipOwner.get().uuid().equals(player.getUniqueId())) {
                player.kick(plugin.component(plugin.msg(
                        "messages.account-collision",
                        "%player%", ipOwner.get().username()
                )));
                return false;
            }
        }

        if (account.isPresent()) {
            AccountRecord record = account.get();
            boolean trustedIp = ipHasher.matches(ip, record.ipHmac());
            boolean trustedIpAutoLogin = plugin.getConfig().getBoolean("security.trusted-ip-auto-login", true);

            if (trustedIp && trustedIpAutoLogin) {
                sessions.put(player.getUniqueId(), new AuthSession(AuthStage.AUTHENTICATED));
                store.updateUsername(player.getUniqueId(), player.getName());
                player.sendMessage(plugin.prefix() + plugin.msg("messages.auto-login"));
                return true;
            }

            sessions.put(player.getUniqueId(), new AuthSession(AuthStage.LOGIN));
            if (trustedIp) {
                player.sendMessage(plugin.prefix() + plugin.msg("messages.login-start"));
            } else {
                player.sendMessage(plugin.prefix() + plugin.msg("messages.new-ip-login-start"));
            }
        } else {
            sessions.put(player.getUniqueId(), new AuthSession(AuthStage.REGISTER));
            player.sendMessage(plugin.prefix() + plugin.msg("messages.register-start", "%length%", Integer.toString(pinLength())));
        }
        return true;
    }

    public AuthResult submitPin(Player player, String pin) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session == null || session.stage() == AuthStage.AUTHENTICATED) {
            return AuthResult.success("");
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
            case AUTHENTICATED -> AuthResult.success("");
        };
    }

    private AuthResult finishRegistration(Player player, AuthSession session, String pin) {
        byte[] first = session.firstPin() == null ? new byte[0] : session.firstPin().getBytes(StandardCharsets.UTF_8);
        byte[] second = pin.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(first, second)) {
            session.firstPin(null);
            session.stage(AuthStage.REGISTER);
            return AuthResult.retry(plugin.msg("messages.pin-mismatch"));
        }

        String ip = currentIp(player);
        if (ip == null) {
            return AuthResult.kick(plugin.msg("messages.no-address"));
        }

        String currentIpHmac = ipHasher.hash(ip);
        if (plugin.getConfig().getBoolean("security.unique-ip-ownership", true)) {
            Optional<AccountRecord> ipOwner = store.findByIpHmac(currentIpHmac);
            if (ipOwner.isPresent() && !ipOwner.get().uuid().equals(player.getUniqueId())) {
                return AuthResult.kick(plugin.msg(
                        "messages.account-collision",
                        "%player%", ipOwner.get().username()
                ));
            }
        }

        PinHasher.Hash hashed = pinHasher.hash(pin);
        AccountRecord record = new AccountRecord(
                player.getUniqueId(),
                player.getName(),
                hashed.salt(),
                hashed.hash(),
                currentIpHmac,
                System.currentTimeMillis()
        );
        store.register(record);
        session.firstPin(null);
        session.stage(AuthStage.AUTHENTICATED);
        return AuthResult.success(plugin.msg("messages.registered"));
    }

    private AuthResult finishLogin(Player player, AuthSession session, String pin) {
        Optional<AccountRecord> account = store.find(player.getUniqueId());
        if (account.isEmpty()) {
            session.stage(AuthStage.REGISTER);
            return AuthResult.next(plugin.msg("messages.register-start", "%length%", Integer.toString(pinLength())));
        }

        AccountRecord record = account.get();
        if (pinHasher.verify(pin, record.pinSalt(), record.pinHash())) {
            session.stage(AuthStage.AUTHENTICATED);
            store.updateUsername(player.getUniqueId(), player.getName());

            String ip = currentIp(player);
            boolean trustedIp = ip != null && ipHasher.matches(ip, record.ipHmac());
            if (!trustedIp) {
                return AuthResult.success(plugin.msg("messages.logged-in-new-ip"));
            }
            return AuthResult.success(plugin.msg("messages.logged-in"));
        }

        int attempts = session.incrementAttempts();
        int maxAttempts = Math.max(1, plugin.getConfig().getInt("security.max-attempts", 5));
        if (attempts >= maxAttempts) {
            return AuthResult.kick(plugin.msg("messages.too-many-attempts"));
        }
        return AuthResult.retry(plugin.msg("messages.wrong-pin", "%remaining%", Integer.toString(maxAttempts - attempts)));
    }

    public void prepareRegistration(Player player) {
        sessions.put(player.getUniqueId(), new AuthSession(AuthStage.REGISTER));
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
    }

    private String currentIp(Player player) {
        InetSocketAddress address = player.getAddress();
        if (address == null || address.getAddress() == null) {
            return null;
        }
        return address.getAddress().getHostAddress();
    }
}
