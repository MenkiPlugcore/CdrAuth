package id.menkiplugcore.cdrauth.command;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;
import id.menkiplugcore.cdrauth.auth.AuthManager;
import id.menkiplugcore.cdrauth.storage.AccountRecord;
import id.menkiplugcore.cdrauth.storage.AccountStore;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class CdrAuthCommand implements CommandExecutor, TabCompleter {
    private final CdrAuthPlugin plugin;
    private final AccountStore store;
    private final AuthManager authManager;

    public CdrAuthCommand(CdrAuthPlugin plugin, AccountStore store, AuthManager authManager) {
        this.plugin = plugin;
        this.store = store;
        this.authManager = authManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("cdrauth.admin")) {
            sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-no-permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-usage"));
            return true;
        }

        if (args[0].equalsIgnoreCase("unreg")) {
            Optional<AccountRecord> removed = store.unregister(args[1]);
            if (removed.isEmpty()) {
                sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-not-found"));
                return true;
            }

            AccountRecord account = removed.get();
            Player online = Bukkit.getPlayer(account.uuid());
            if (online != null) {
                authManager.prepareRegistration(online);
                plugin.showAuth(online);
            }
            sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-unregistered", "%player%", account.username()));
            return true;
        }

        if (args[0].equalsIgnoreCase("status")) {
            Optional<AccountRecord> account = store.findByQuery(args[1]);
            if (account.isEmpty()) {
                sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-not-found"));
                return true;
            }
            AccountRecord record = account.get();
            sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-status",
                    "%player%", record.username(),
                    "%uuid%", record.uuid().toString()));
            return true;
        }

        sender.sendMessage(plugin.prefix() + plugin.msg("messages.admin-usage"));
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("unreg", "status").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        if (args.length == 2) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(args[1].toLowerCase())) {
                    names.add(player.getName());
                }
            }
            return names;
        }
        return List.of();
    }
}
