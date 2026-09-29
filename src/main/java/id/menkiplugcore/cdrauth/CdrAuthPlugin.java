package id.menkiplugcore.cdrauth;

import id.menkiplugcore.cdrauth.auth.AuthManager;
import id.menkiplugcore.cdrauth.auth.AuthResult;
import id.menkiplugcore.cdrauth.auth.AuthStage;
import id.menkiplugcore.cdrauth.command.CdrAuthCommand;
import id.menkiplugcore.cdrauth.command.PinCommand;
import id.menkiplugcore.cdrauth.listener.AuthListener;
import id.menkiplugcore.cdrauth.storage.AccountStore;
import id.menkiplugcore.cdrauth.ui.AdminGui;
import id.menkiplugcore.cdrauth.ui.AuthUxController;
import id.menkiplugcore.cdrauth.ui.BedrockPinUi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CdrAuthPlugin extends JavaPlugin {
    private AccountStore accountStore;
    private AuthManager authManager;
    private BedrockPinUi bedrockPinUi;
    private AdminGui adminGui;
    private AuthUxController authUxController;
    private final Set<UUID> chatFallback = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        try {
            this.accountStore = new AccountStore(this);
            this.authManager = new AuthManager(this, accountStore);
        } catch (Exception exception) {
            getLogger().severe("CdrAuth failed to initialize secure storage: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.bedrockPinUi = new BedrockPinUi(this, authManager);
        this.adminGui = new AdminGui(this, accountStore, authManager);
        this.authUxController = new AuthUxController(this);

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
        getLogger().info("Java PIN input: private cancelled chat + login UX | Floodgate native UI: "
                + (bedrockPinUi.isAvailable() ? "available" : "not detected"));
    }

    @Override
    public void onDisable() {
        if (authManager != null) {
            authManager.clearSessions();
        }
        chatFallback.clear();
    }

    public void showAuth(Player player) {
        if (!player.isOnline() || !authManager.needsAuthentication(player.getUniqueId())) {
            return;
        }

        if (bedrockPinUi.isBedrock(player) && !chatFallback.contains(player.getUniqueId())) {
            bedrockPinUi.open(player);
            return;
        }

        AuthStage stage = authManager.stage(player.getUniqueId());
        authUxController.begin(player, stage);
        showChatPrompt(player, stage);
    }

    public void showJavaFallback(Player player) {
        if (player.isOnline() && authManager.needsAuthentication(player.getUniqueId())) {
            chatFallback.add(player.getUniqueId());
            AuthStage stage = authManager.stage(player.getUniqueId());
            authUxController.begin(player, stage);
            showChatPrompt(player, stage);
        }
    }

    public boolean acceptsChatPin(Player player) {
        return !bedrockPinUi.isBedrock(player) || chatFallback.contains(player.getUniqueId());
    }

    public void handleAuthResult(Player player, AuthResult result) {
        switch (result.type()) {
            case SUCCESS -> {
                bedrockPinUi.clear(player.getUniqueId());
                chatFallback.remove(player.getUniqueId());
                authUxController.finish(player);
                if (!result.message().isBlank()) {
                    player.sendMessage(prefix() + result.message());
                }
            }
            case NEXT, RETRY -> {
                player.sendMessage(prefix() + result.message());
                showAuth(player);
            }
            case KICK -> player.kick(component(result.message()));
        }
    }

    public void cleanupUi(Player player) {
        bedrockPinUi.clear(player.getUniqueId());
        chatFallback.remove(player.getUniqueId());
        if (authUxController != null) {
            authUxController.cleanup(player);
        }
    }

    private void showChatPrompt(Player player, AuthStage stage) {
        String promptPath = switch (stage) {
            case REGISTER -> "messages.java-chat-register-prompt";
            case CONFIRM_REGISTER -> "messages.java-chat-confirm-register-prompt";
            case LOGIN -> "messages.java-chat-login-prompt";
            case RESET_PIN -> "messages.java-chat-reset-pin-prompt";
            case CONFIRM_RESET_PIN -> "messages.java-chat-confirm-reset-pin-prompt";
            case CHANGE_PIN_VERIFY -> "messages.java-chat-change-verify-prompt";
            case CHANGE_PIN_NEW -> "messages.java-chat-change-new-prompt";
            case CONFIRM_CHANGE_PIN -> "messages.java-chat-change-confirm-prompt";
            case AUTHENTICATED -> "messages.java-chat-login-prompt";
        };

        player.sendMessage(msg("messages.java-chat-header"));
        player.sendMessage(msg(promptPath, "%length%", Integer.toString(authManager.pinLength())));
        player.sendMessage(msg("messages.java-chat-private-note"));
        player.sendMessage(msg("messages.java-chat-footer"));
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
