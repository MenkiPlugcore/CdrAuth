package id.menkiplugcore.cdrauth.ui;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;
import id.menkiplugcore.cdrauth.auth.AuthManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BedrockPinUi {
    private final CdrAuthPlugin plugin;
    private final AuthManager authManager;
    private final Map<UUID, StringBuilder> input = new HashMap<>();
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
        input.put(player.getUniqueId(), new StringBuilder());
        send(player);
    }

    void digit(Player player, String digit) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !authManager.needsAuthentication(player.getUniqueId())) {
                return;
            }
            StringBuilder builder = input.computeIfAbsent(player.getUniqueId(), ignored -> new StringBuilder());
            if (builder.length() < authManager.pinLength()) {
                builder.append(digit);
            }
            send(player);
        });
    }

    void backspace(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            StringBuilder builder = input.computeIfAbsent(player.getUniqueId(), ignored -> new StringBuilder());
            if (!builder.isEmpty()) {
                builder.deleteCharAt(builder.length() - 1);
            }
            if (player.isOnline() && authManager.needsAuthentication(player.getUniqueId())) {
                send(player);
            }
        });
    }

    void submit(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            String pin = input.getOrDefault(player.getUniqueId(), new StringBuilder()).toString();
            plugin.handleAuthResult(player, authManager.submitPin(player, pin));
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
        input.remove(uuid);
    }

    private void send(Player player) {
        if (bridge == null) {
            plugin.showJavaFallback(player);
            return;
        }
        StringBuilder builder = input.computeIfAbsent(player.getUniqueId(), ignored -> new StringBuilder());
        String masked = "●".repeat(builder.length()) + "○".repeat(Math.max(0, authManager.pinLength() - builder.length()));
        if (!bridge.send(player, masked)) {
            plugin.showJavaFallback(player);
        }
    }
}
