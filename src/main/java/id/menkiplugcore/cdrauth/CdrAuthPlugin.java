package id.menkiplugcore.cdrauth;

import id.menkiplugcore.cdrauth.auth.AuthManager;
import id.menkiplugcore.cdrauth.auth.AuthResult;
import id.menkiplugcore.cdrauth.command.CdrAuthCommand;
import id.menkiplugcore.cdrauth.command.PinCommand;
import id.menkiplugcore.cdrauth.listener.AuthListener;
import id.menkiplugcore.cdrauth.storage.AccountStore;
import id.menkiplugcore.cdrauth.ui.AdminGui;
import id.menkiplugcore.cdrauth.ui.BedrockPinUi;
import id.menkiplugcore.cdrauth.ui.JavaPinGui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class CdrAuthPlugin extends JavaPlugin {
    private AccountStore accountStore;
    private AuthManager authManager;
    private JavaPinGui javaPinGui;
    private BedrockPinUi bedrockPinUi;
    private AdminGui adminGui;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        try {
            this.accountStore = new AccountStore(this);
            this.authManager = new AuthManager(this, accountStore);
        } catch (Exception exception) {
            getLogger().severe("CdrAuth failed to initialize secure storage: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.javaPinGui = new JavaPinGui(this, authManager);
        this.bedrockPinUi = new BedrockPinUi(this, authManager);
        this.adminGui = new AdminGui(this, accountStore, authManager);

        getServer().getPluginManager().registerEvents(javaPinGui, this);
        getServer().getPluginManager().registerEvents(adminGui, this);
        getServer().getPluginManager().registerEvents(new AuthListener(this, authManager), this);

        CdrAuthCommand command = new CdrAuthCommand(this, accountStore, authManager, adminGui);
        if (getCommand("cdrauth") != null) {
            getCommand("cdrauth").setExecutor(command);
            getCommand("cdrauth").setTabCompleter(command);
        }

        PinCommand pinCommand = new PinCommand(this, authManager);
        if (getCommand("pin") != null) {
            getCommand("pin").setExecutor(pinCommand);
        }

        getLogger().info("CdrAuth v" + getDescription().getVersion() + " enabled.");
        getLogger().info("PIN hashing: PBKDF2-HMAC-SHA256 | trusted IP auto-login: "
                + getConfig().getBoolean("security.trusted-ip-auto-login", true)
                + " | unique IP ownership: "
                + getConfig().getBoolean("security.unique-ip-ownership", true)
                + " | change PIN requires trusted IP: "
                + getConfig().getBoolean("security.change-pin.require-trusted-ip", true));
        getLogger().info("Floodgate native UI: " + (bedrockPinUi.isAvailable() ? "available" : "not detected (Java GUI fallback)"));
    }

    @Override
    public void onDisable() {
        if (authManager != null) {
            authManager.clearSessions();
        }
    }

    public void showAuth(Player player) {
        if (!player.isOnline() || !authManager.needsAuthentication(player.getUniqueId())) {
            return;
        }

        if (bedrockPinUi.isBedrock(player)) {
            bedrockPinUi.open(player);
        } else {
            javaPinGui.open(player);
        }
    }

    public void showJavaFallback(Player player) {
        if (player.isOnline() && authManager.needsAuthentication(player.getUniqueId())) {
            javaPinGui.open(player);
        }
    }

    public void handleAuthResult(Player player, AuthResult result) {
        switch (result.type()) {
            case SUCCESS -> {
                javaPinGui.closeSilently(player);
                bedrockPinUi.clear(player.getUniqueId());
                player.sendMessage(prefix() + result.message());
            }
            case NEXT, RETRY -> {
                player.sendMessage(prefix() + result.message());
                showAuth(player);
            }
            case KICK -> player.kick(component(result.message()));
        }
    }

    public void cleanupUi(Player player) {
        javaPinGui.cleanup(player.getUniqueId());
        bedrockPinUi.clear(player.getUniqueId());
    }

    public String msg(String path, String... replacements) {
        String value = getConfig().getString(path, path);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            value = value.replace(replacements[i], replacements[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    public String prefix() {
        return msg("messages.prefix");
    }

    public Component component(String legacyText) {
        return LegacyComponentSerializer.legacySection().deserialize(legacyText);
    }

    public AuthManager authManager() {
        return authManager;
    }

    public AdminGui adminGui() {
        return adminGui;
    }
}
