package id.menkiplugcore.cdrauth.command;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;
import id.menkiplugcore.cdrauth.auth.AuthManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public final class PinCommand implements CommandExecutor {
    private final CdrAuthPlugin plugin;
    private final AuthManager authManager;

    public PinCommand(CdrAuthPlugin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.prefix() + plugin.msg("messages.change-pin-player-only"));
            return true;
        }

        if (!player.hasPermission("cdrauth.changepin")) {
            player.sendMessage(plugin.prefix() + plugin.msg("messages.change-pin-no-permission"));
            return true;
        }

        if (authManager.beginPinChange(player)) {
            plugin.showAuth(player);
        }
        return true;
    }
}
