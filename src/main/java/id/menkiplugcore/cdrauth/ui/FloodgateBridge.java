package id.menkiplugcore.cdrauth.ui;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;
import id.menkiplugcore.cdrauth.auth.AuthStage;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
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

    boolean send(Player player) {
        try {
            int length = plugin.authManager().pinLength();
            CustomForm.Builder form = CustomForm.builder()
                    .title(title(player))
                    .label("Masukkan PIN " + length + " digit. PIN tidak dikirim melalui chat.")
                    .input("PIN", "Contoh: " + "1".repeat(length));

            form.validResultHandler(response -> owner.submit(player, response.asInput(0)));
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
            case RESET_PIN -> "CdrAuth • Reset PIN";
            case CONFIRM_RESET_PIN -> "CdrAuth • Confirm New PIN";
            case CHANGE_PIN_VERIFY -> "CdrAuth • Verify Old PIN";
            case CHANGE_PIN_NEW -> "CdrAuth • New PIN";
            case CONFIRM_CHANGE_PIN -> "CdrAuth • Confirm New PIN";
            case LOGIN, AUTHENTICATED -> "CdrAuth • Login";
        };
    }
}
