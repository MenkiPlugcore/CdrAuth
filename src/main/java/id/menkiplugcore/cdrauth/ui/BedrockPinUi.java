package id.menkiplugcore.cdrauth.ui;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;
import id.menkiplugcore.cdrauth.auth.AuthManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class BedrockPinUi {
    private final CdrAuthPlugin plugin;
    private final AuthManager authManager;
    private final FloodgateBridge bridge;

    public BedrockPinUi(CdrAuthPlugin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
        FloodgateBridge candidate = null;
        if (plugin.getServer().getPluginManager().isPluginEnabled("floodgate")) {
            try {
                candidate = new FloodgateBridge(plugin, this);
            } catch (Throwable throwable) {
                plugin.getLogger().warning("Floodgate detected but CdrAuth could not initialize its native form bridge: " + throwable.getMessage());
            }
        }
        this.bridge = candidate;
    }

    public boolean isAvailable() {
        return bridge != null;
    }

    public boolean isBedrock(Player player) {
        return bridge != null && bridge.isBedrock(player.getUniqueId());
    }

    public void open(Player player) {
        send(player);
    }

    void submit(Player player, String pin) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !authManager.needsAuthentication(player.getUniqueId())) {
                return;
            }
            plugin.handleAuthResult(player, authManager.submitPin(player, pin == null ? "" : pin.trim()));
        });
    }

    void closed(Player player) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && authManager.needsAuthentication(player.getUniqueId()) && isBedrock(player)) {
                send(player);
            }
        }, Math.max(1L, plugin.getConfig().getLong("security.reopen-delay-ticks", 8L)));
    }

    public void clear(UUID uuid) {
        // Typed Bedrock forms keep no server-side PIN buffer.
    }

    private void send(Player player) {
        if (bridge == null) {
            plugin.showJavaFallback(player);
            return;
        }
        if (!bridge.send(player)) {
            plugin.showJavaFallback(player);
        }
    }
}
