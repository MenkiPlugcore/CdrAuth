package id.menkiplugcore.cdrauth.ui;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;
import id.menkiplugcore.cdrauth.auth.AuthStage;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.UUID;

final class FloodgateBridge {
    private final CdrAuthPlugin plugin;
    private final BedrockPinUi owner;
    private final FloodgateApi api;

    FloodgateBridge(CdrAuthPlugin plugin, BedrockPinUi owner) {
        this.plugin = plugin;
        this.owner = owner;
        this.api = FloodgateApi.getInstance();
    }

    boolean isBedrock(UUID uuid) {
        try {
            return api.isFloodgatePlayer(uuid);
        } catch (Throwable throwable) {
            return false;
        }
    }

    boolean send(Player player, String masked) {
        try {
            SimpleForm.Builder form = SimpleForm.builder()
                    .title(title(player))
                    .content("PIN: " + masked + "\n\nPilih angka di bawah.");

            for (int number = 1; number <= 9; number++) {
                String digit = Integer.toString(number);
                form.button(digit, response -> owner.digit(player, digit));
            }
            form.button("⌫ Hapus", response -> owner.backspace(player));
            form.button("0", response -> owner.digit(player, "0"));
            form.button("✔ Konfirmasi", response -> owner.submit(player));
            form.closedOrInvalidResultHandler(() -> owner.closed(player));
            return api.sendForm(player.getUniqueId(), form);
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Failed to send Floodgate auth form to " + player.getName() + ": " + throwable.getMessage());
            return false;
        }
    }

    private String title(Player player) {
        AuthStage stage = plugin.authManager().stage(player.getUniqueId());
        return switch (stage) {
            case REGISTER -> "CdrAuth • Register";
            case CONFIRM_REGISTER -> "CdrAuth • Confirm PIN";
            case LOGIN, AUTHENTICATED -> "CdrAuth • Login";
        };
    }
}
