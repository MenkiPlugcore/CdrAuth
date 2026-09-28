package id.menkiplugcore.cdrauth.command;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;
import id.menkiplugcore.cdrauth.auth.AuthManager;
import id.menkiplugcore.cdrauth.auth.SecurityAuditLogger;
import id.menkiplugcore.cdrauth.storage.AccountRecord;
import id.menkiplugcore.cdrauth.storage.AccountStore;
import id.menkiplugcore.cdrauth.ui.AdminGui;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

public final class CdrAuthCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final CdrAuthPlugin plugin;
    private final AccountStore store;
    private final AuthManager authManager;
    private final AdminGui adminGui;
    private final SecurityAuditLogger auditLogger;

    public CdrAuthCommand(CdrAuthPlugin plugin, AccountStore store, AuthManager authManager, AdminGui adminGui) {
        this.plugin = plugin;
        this.store = store;
        this.authManager = authManager;
        this.adminGui = adminGui;

        SecurityAuditLogger logger = null;
        try {
            logger = new SecurityAuditLogger(plugin);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not initialize admin audit logger: " + exception.getMessage());
        }
        this.auditLogger = logger;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("cdrauth.admin")) {
            sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-no-permission"));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-usage"));
            return true;
        }

        String action = args[0].toLowerCase();

        if (action.equals("admin")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-gui-player-only"));
                return true;
            }
            if (args.length >= 2) {
                adminGui.openDetail(player, args[1]);
            } else {
                adminGui.openList(player, 0);
            }
            return true;
        }

        if (action.equals("audit")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-gui-player-only"));
                return true;
            }
            adminGui.openAudit(player, args.length >= 2 ? args[1] : null);
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-usage"));
            return true;
        }

        String query = args[1];

        if (action.equals("unreg")) {
            Optional<AccountRecord> removed = store.unregister(query);
            if (removed.isEmpty()) {
                sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-not-found"));
                return true;
            }

            AccountRecord account = removed.get();
            auditAdmin("ADMIN_UNREGISTER", sender, account, "accountRemoved=true");
            Player online = Bukkit.getPlayer(account.uuid());
            if (online != null) {
                authManager.prepareRegistration(online);
                plugin.showAuth(online);
            }
            sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-unregistered", "%player%", account.username()));
            return true;
        }

        if (action.equals("resetip")) {
            Optional<AccountRecord> found = store.findByQuery(query);
            if (found.isEmpty()) {
                sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-not-found"));
                return true;
            }

            AccountRecord account = found.get();
            if (account.pinResetRequired()) {
                sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-resetip-blocked-pinreset", "%player%", account.username()));
                return true;
            }

            auditAdmin("ADMIN_RESET_IP", sender, account, "trustedIpCleared=true");
            store.resetTrustedIp(query);
            Player online = Bukkit.getPlayer(account.uuid());
            if (online != null && authManager.prepareIpRebind(online)) {
                plugin.showAuth(online);
            }
            sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-ip-reset", "%player%", account.username()));
            return true;
        }

        if (action.equals("resetpin")) {
            Optional<AccountRecord> found = store.findByQuery(query);
            if (found.isEmpty()) {
                sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-not-found"));
                return true;
            }

            AccountRecord account = found.get();
            if (!account.hasTrustedIp()) {
                sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-resetpin-no-ip", "%player%", account.username()));
                return true;
            }

            auditAdmin("ADMIN_RESET_PIN", sender, account, "pinResetRequired=true");
            store.requirePinReset(query);
            Player online = Bukkit.getPlayer(account.uuid());
            if (online != null && authManager.preparePinReset(online)) {
                plugin.showAuth(online);
            }
            sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-pin-reset", "%player%", account.username()));
            return true;
        }

        if (action.equals("status")) {
            Optional<AccountRecord> account = store.findByQuery(query);
            if (account.isEmpty()) {
                sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-not-found"));
                return true;
            }

            AccountRecord record = account.get();
            Player online = Bukkit.getPlayer(record.uuid());
            String onlineState = online == null ? "OFFLINE" : "ONLINE";
            String ipState = record.hasTrustedIp() ? "BOUND" : "RESET_PENDING";
            String pinState = record.pinResetRequired() ? "RESET_REQUIRED" : "ACTIVE";
            String sessionState = online == null
                    ? "N/A"
                    : (authManager.needsAuthentication(record.uuid()) ? authManager.stage(record.uuid()).name() : "AUTHENTICATED");
            String created = DATE_FORMAT.format(Instant.ofEpochMilli(record.createdAt()));

            sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-status-header", "%player%", record.username()));
            sender.sendMessage(plugin.msg("messages.admin-status-uuid", "%uuid%", record.uuid().toString()));
            sender.sendMessage(plugin.msg("messages.admin-status-online", "%online%", onlineState));
            sender.sendMessage(plugin.msg("messages.admin-status-ip", "%ipstate%", ipState));
            sender.sendMessage(plugin.msg("messages.admin-status-pin", "%pinstate%", pinState));
            sender.sendMessage(plugin.msg("messages.admin-status-session", "%session%", sessionState));
            sender.sendMessage(plugin.msg("messages.admin-status-created", "%created%", created));
            return true;
        }

        sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-usage"));
        return true;
    }

    private void auditAdmin(String event, CommandSender actor, AccountRecord target, String detail) {
        if (auditLogger == null) {
            return;
        }
        String fingerprint = target.hasTrustedIp()
                ? target.ipHmac().substring(0, Math.min(12, target.ipHmac().length()))
                : "none";
        auditLogger.log(
                event,
                target.username(),
                target.uuid().toString(),
                fingerprint,
                "actor=" + actor.getName() + " " + detail
        );
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("cdrauth.admin")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("admin", "audit", "status", "resetip", "resetpin", "unreg").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase();
            return store.listAll().stream()
                    .map(AccountRecord::username)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .limit(100)
                    .toList();
        }
        return List.of();
    }
}
