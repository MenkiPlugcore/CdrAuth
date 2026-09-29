package id.menkiplugcore.cdrauth.ui;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;
import id.menkiplugcore.cdrauth.auth.AuthStage;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AuthUxController {
    private static final Set<AuthStage> LOGIN_STAGES = EnumSet.of(
            AuthStage.REGISTER,
            AuthStage.CONFIRM_REGISTER,
            AuthStage.LOGIN,
            AuthStage.RESET_PIN,
            AuthStage.CONFIRM_RESET_PIN
    );

    private final CdrAuthPlugin plugin;
    private final Map<UUID, SavedEffects> savedEffects = new HashMap<>();

    public AuthUxController(CdrAuthPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean usesLoginUx(AuthStage stage) {
        return LOGIN_STAGES.contains(stage);
    }

    public void begin(Player player, AuthStage stage) {
        if (!usesLoginUx(stage)) {
            return;
        }

        UUID uuid = player.getUniqueId();
        if (!savedEffects.containsKey(uuid)) {
            savedEffects.put(uuid, new SavedEffects(
                    player.getPotionEffect(PotionEffectType.SLOWNESS),
                    player.getPotionEffect(PotionEffectType.BLINDNESS)
            ));
            clearChat(player);
        }

        int slownessAmplifier = Math.max(0, plugin.getConfig().getInt("security.login-ux.slowness-amplifier", 4));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOWNESS,
                Integer.MAX_VALUE,
                slownessAmplifier,
                false,
                false,
                false
        ));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.BLINDNESS,
                Integer.MAX_VALUE,
                0,
                false,
                false,
                false
        ));
    }

    public void finish(Player player) {
        if (restoreEffects(player)) {
            player.sendMessage("");
            player.sendMessage(plugin.msg("messages.java-chat-restored"));
            player.sendMessage("");
        }
    }

    public void cleanup(Player player) {
        restoreEffects(player);
    }

    private boolean restoreEffects(Player player) {
        SavedEffects saved = savedEffects.remove(player.getUniqueId());
        if (saved == null) {
            return false;
        }

        player.removePotionEffect(PotionEffectType.SLOWNESS);
        player.removePotionEffect(PotionEffectType.BLINDNESS);

        if (saved.slowness() != null) {
            player.addPotionEffect(saved.slowness());
        }
        if (saved.blindness() != null) {
            player.addPotionEffect(saved.blindness());
        }
        return true;
    }

    private void clearChat(Player player) {
        int lines = Math.max(20, Math.min(200, plugin.getConfig().getInt("security.login-ux.clear-chat-lines", 80)));
        for (int i = 0; i < lines; i++) {
            player.sendMessage("");
        }
    }

    private record SavedEffects(PotionEffect slowness, PotionEffect blindness) {
    }
}
