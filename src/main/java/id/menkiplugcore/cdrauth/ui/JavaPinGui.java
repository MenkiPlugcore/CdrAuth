package id.menkiplugcore.cdrauth.ui;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;
import id.menkiplugcore.cdrauth.auth.AuthManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class JavaPinGui implements Listener {
    private static final int INPUT_SLOT = 0;
    private static final int RESULT_SLOT = 2;

    private final CdrAuthPlugin plugin;
    private final AuthManager authManager;
    private final Set<UUID> suppressClose = new HashSet<>();

    public JavaPinGui(CdrAuthPlugin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    public void open(Player player) {
        if (!authManager.needsAuthentication(player.getUniqueId())) {
            return;
        }

        if (player.getOpenInventory().getTopInventory().getHolder() instanceof PinGuiHolder) {
            suppressClose.add(player.getUniqueId());
        }

        PinGuiHolder holder = new PinGuiHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, InventoryType.ANVIL, titleFor(player));
        holder.inventory(inventory);

        ItemStack input = new ItemStack(Material.PAPER);
        ItemMeta meta = input.getItemMeta();
        meta.setDisplayName("");
        meta.setLore(List.of(
                "§7Ketik PIN §f" + authManager.pinLength() + " digit §7di kolom nama di atas.",
                "§8PIN tidak dikirim melalui chat."
        ));
        input.setItemMeta(meta);
        inventory.setItem(INPUT_SLOT, input);

        player.openInventory(inventory);
    }

    @EventHandler
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getInventory().getHolder() instanceof PinGuiHolder holder)) {
            return;
        }
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        if (!holder.owner().equals(player.getUniqueId())) {
            return;
        }

        String pin = event.getView().getRenameText();
        if (pin == null || !pin.matches("\\d{" + authManager.pinLength() + "}")) {
            event.setResult(null);
            return;
        }

        ItemStack result = new ItemStack(Material.LIME_DYE);
        ItemMeta meta = result.getItemMeta();
        meta.setDisplayName(plugin.msg("messages.gui-submit"));
        meta.setLore(List.of("§7Klik untuk mengirim PIN dari GUI."));
        result.setItemMeta(meta);
        event.setResult(result);
        event.getView().setRepairCost(0);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof PinGuiHolder holder)) {
            return;
        }
        if (!holder.owner().equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        if (event.getRawSlot() != RESULT_SLOT) {
            return;
        }
        if (!(event.getView() instanceof AnvilView anvilView)) {
            return;
        }

        String pin = anvilView.getRenameText();
        if (pin == null || !pin.matches("\\d{" + authManager.pinLength() + "}")) {
            return;
        }

        plugin.handleAuthResult(player, authManager.submitPin(player, pin));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!(event.getInventory().getHolder() instanceof PinGuiHolder)) {
            return;
        }

        if (suppressClose.remove(player.getUniqueId())) {
            return;
        }

        if (authManager.needsAuthentication(player.getUniqueId()) && player.isOnline()) {
            long delay = Math.max(1L, plugin.getConfig().getLong("security.reopen-delay-ticks", 8L));
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.showAuth(player), delay);
        }
    }

    public void closeSilently(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof PinGuiHolder) {
            suppressClose.add(player.getUniqueId());
            player.closeInventory();
        }
    }

    public void cleanup(UUID uuid) {
        suppressClose.remove(uuid);
    }

    private String titleFor(Player player) {
        String title = switch (authManager.stage(player.getUniqueId())) {
            case REGISTER -> plugin.msg("messages.gui-register-title");
            case CONFIRM_REGISTER -> plugin.msg("messages.gui-confirm-title");
            case RESET_PIN -> plugin.msg("messages.gui-reset-pin-title");
            case CONFIRM_RESET_PIN -> plugin.msg("messages.gui-confirm-reset-pin-title");
            case CHANGE_PIN_VERIFY -> plugin.msg("messages.gui-change-pin-verify-title");
            case CHANGE_PIN_NEW -> plugin.msg("messages.gui-change-pin-new-title");
            case CONFIRM_CHANGE_PIN -> plugin.msg("messages.gui-change-pin-confirm-title");
            case LOGIN, AUTHENTICATED -> plugin.msg("messages.gui-login-title");
        };
        return title + " §8(" + authManager.pinLength() + " digit)";
    }
}
