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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class JavaPinGui implements Listener {
    private static final Map<Integer, String> DIGIT_SLOTS = Map.of(
            10, "1", 11, "2", 12, "3",
            19, "4", 20, "5", 21, "6",
            28, "7", 29, "8", 30, "9",
            32, "0"
    );
    private static final int BACKSPACE_SLOT = 31;
    private static final int SUBMIT_SLOT = 33;

    private final CdrAuthPlugin plugin;
    private final AuthManager authManager;
    private final Map<UUID, StringBuilder> input = new HashMap<>();
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

        input.put(player.getUniqueId(), new StringBuilder());
        PinGuiHolder holder = new PinGuiHolder(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(holder, 36, titleFor(player));
        holder.inventory(inventory);

        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        for (Map.Entry<Integer, String> entry : DIGIT_SLOTS.entrySet()) {
            inventory.setItem(entry.getKey(), item(Material.LIGHT_BLUE_STAINED_GLASS_PANE, "§b§l" + entry.getValue()));
        }
        inventory.setItem(BACKSPACE_SLOT, item(Material.YELLOW_STAINED_GLASS_PANE, plugin.msg("messages.gui-backspace")));
        inventory.setItem(SUBMIT_SLOT, item(Material.LIME_STAINED_GLASS_PANE, plugin.msg("messages.gui-submit")));
        renderDisplay(inventory, player.getUniqueId());
        player.openInventory(inventory);
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
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        StringBuilder builder = input.computeIfAbsent(player.getUniqueId(), ignored -> new StringBuilder());
        String digit = DIGIT_SLOTS.get(event.getRawSlot());
        if (digit != null) {
            if (builder.length() < authManager.pinLength()) {
                builder.append(digit);
                renderDisplay(event.getView().getTopInventory(), player.getUniqueId());
            }
            return;
        }

        if (event.getRawSlot() == BACKSPACE_SLOT) {
            if (!builder.isEmpty()) {
                builder.deleteCharAt(builder.length() - 1);
                renderDisplay(event.getView().getTopInventory(), player.getUniqueId());
            }
            return;
        }

        if (event.getRawSlot() == SUBMIT_SLOT) {
            plugin.handleAuthResult(player, authManager.submitPin(player, builder.toString()));
        }
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
        input.remove(player.getUniqueId());
    }

    public void cleanup(UUID uuid) {
        input.remove(uuid);
        suppressClose.remove(uuid);
    }

    private void renderDisplay(Inventory inventory, UUID uuid) {
        int length = input.getOrDefault(uuid, new StringBuilder()).length();
        String masked = "●".repeat(length) + "○".repeat(Math.max(0, authManager.pinLength() - length));
        inventory.setItem(4, item(Material.PAPER, plugin.msg("messages.gui-pin-display", "%pin%", masked)));
    }

    private String titleFor(Player player) {
        return switch (authManager.stage(player.getUniqueId())) {
            case REGISTER -> plugin.msg("messages.gui-register-title");
            case CONFIRM_REGISTER -> plugin.msg("messages.gui-confirm-title");
            case RESET_PIN -> plugin.msg("messages.gui-reset-pin-title");
            case CONFIRM_RESET_PIN -> plugin.msg("messages.gui-confirm-reset-pin-title");
            case CHANGE_PIN_VERIFY -> plugin.msg("messages.gui-change-pin-verify-title");
            case CHANGE_PIN_NEW -> plugin.msg("messages.gui-change-pin-new-title");
            case CONFIRM_CHANGE_PIN -> plugin.msg("messages.gui-change-pin-confirm-title");
            case LOGIN, AUTHENTICATED -> plugin.msg("messages.gui-login-title");
        };
    }

    private ItemStack item(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        stack.setItemMeta(meta);
        return stack;
    }
}
