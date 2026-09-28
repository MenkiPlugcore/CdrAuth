package id.menkiplugcore.cdrauth.ui;

import id.menkiplugcore.cdrauth.CdrAuthPlugin;
import id.menkiplugcore.cdrauth.auth.AuditEntry;
import id.menkiplugcore.cdrauth.auth.AuthManager;
import id.menkiplugcore.cdrauth.auth.SecurityAuditLogger;
import id.menkiplugcore.cdrauth.storage.AccountRecord;
import id.menkiplugcore.cdrauth.storage.AccountStore;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class AdminGui implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final CdrAuthPlugin plugin;
    private final AccountStore store;
    private final AuthManager authManager;
    private final SecurityAuditLogger auditLogger;

    public AdminGui(CdrAuthPlugin plugin, AccountStore store, AuthManager authManager) {
        this.plugin = plugin;
        this.store = store;
        this.authManager = authManager;

        SecurityAuditLogger reader = null;
        try {
            reader = new SecurityAuditLogger(plugin);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not initialize CdrAuth audit viewer: " + exception.getMessage());
        }
        this.auditLogger = reader;
    }

    public void openList(Player admin, int requestedPage) {
        if (!admin.hasPermission("cdrauth.admin")) {
            admin.sendMessage(plugin.prefix() + plugin.msg("messages.admin-no-permission"));
            return;
        }

        List<AccountRecord> accounts = store.listAll();
        int maxPage = Math.max(0, (accounts.size() - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, maxPage));

        AdminGuiHolder holder = new AdminGuiHolder(AdminGuiHolder.View.LIST, page, null, null);
        Inventory inventory = Bukkit.createInventory(holder, 54, plugin.msg(
                "messages.admin-gui-list-title",
                "%page%", Integer.toString(page + 1),
                "%pages%", Integer.toString(maxPage + 1)
        ));
        holder.inventory(inventory);

        int from = page * PAGE_SIZE;
        int to = Math.min(accounts.size(), from + PAGE_SIZE);
        for (int index = from; index < to; index++) {
            AccountRecord record = accounts.get(index);
            inventory.setItem(index - from, accountHead(record));
        }

        if (page > 0) {
            inventory.setItem(45, item(Material.ARROW, plugin.msg("messages.admin-gui-prev"), List.of()));
        }
        inventory.setItem(47, item(Material.COMPASS, plugin.msg("messages.admin-gui-search"), List.of(
                plugin.msg("messages.admin-gui-search-lore")
        )));
        inventory.setItem(49, item(Material.BOOK, plugin.msg(
                "messages.admin-gui-summary",
                "%accounts%", Integer.toString(accounts.size())
        ), List.of(
                plugin.msg("messages.admin-gui-page", "%page%", Integer.toString(page + 1), "%pages%", Integer.toString(maxPage + 1)),
                plugin.msg("messages.admin-gui-refresh-lore")
        )));
        inventory.setItem(51, item(Material.WRITABLE_BOOK, plugin.msg("messages.admin-gui-audit"), List.of(
                plugin.msg("messages.admin-gui-audit-global-lore")
        )));
        if (page < maxPage) {
            inventory.setItem(53, item(Material.ARROW, plugin.msg("messages.admin-gui-next"), List.of()));
        }

        admin.openInventory(inventory);
    }

    public boolean openDetail(Player admin, String query) {
        Optional<AccountRecord> record = store.findByQuery(query);
        if (record.isEmpty()) {
            admin.sendMessage(plugin.prefix() + plugin.msg("messages.admin-not-found"));
            return false;
        }
        openDetail(admin, record.get().uuid(), 0);
        return true;
    }

    public boolean openAudit(Player admin, String query) {
        if (query == null || query.isBlank()) {
            openAudit(admin, null, 0, "ALL");
            return true;
        }
        Optional<AccountRecord> record = store.findByQuery(query);
        if (record.isEmpty()) {
            admin.sendMessage(plugin.prefix() + plugin.msg("messages.admin-not-found"));
            return false;
        }
        openAudit(admin, record.get().uuid(), 0, "ALL");
        return true;
    }

    private void openDetail(Player admin, UUID uuid, int returnPage) {
        Optional<AccountRecord> optional = store.find(uuid);
        if (optional.isEmpty()) {
            admin.closeInventory();
            admin.sendMessage(plugin.prefix() + plugin.msg("messages.admin-not-found"));
            return;
        }

        AccountRecord record = optional.get();
        AdminGuiHolder holder = new AdminGuiHolder(AdminGuiHolder.View.DETAIL, returnPage, uuid, null);
        Inventory inventory = Bukkit.createInventory(holder, 27, plugin.msg(
                "messages.admin-gui-detail-title",
                "%player%", record.username()
        ));
        holder.inventory(inventory);

        inventory.setItem(4, accountHead(record));
        inventory.setItem(10, item(Material.COMPASS, plugin.msg("messages.admin-gui-resetip"), List.of(
                plugin.msg("messages.admin-gui-resetip-lore-1"),
                plugin.msg("messages.admin-gui-resetip-lore-2")
        )));
        inventory.setItem(12, item(Material.TRIPWIRE_HOOK, plugin.msg("messages.admin-gui-resetpin"), List.of(
                plugin.msg("messages.admin-gui-resetpin-lore-1"),
                plugin.msg("messages.admin-gui-resetpin-lore-2")
        )));
        inventory.setItem(14, statusItem(record));
        inventory.setItem(16, item(Material.BARRIER, plugin.msg("messages.admin-gui-unreg"), List.of(
                plugin.msg("messages.admin-gui-unreg-lore-1"),
                plugin.msg("messages.admin-gui-unreg-lore-2")
        )));
        inventory.setItem(18, item(Material.ARROW, plugin.msg("messages.admin-gui-back"), List.of()));
        inventory.setItem(20, item(Material.WRITABLE_BOOK, plugin.msg("messages.admin-gui-audit"), List.of(
                plugin.msg("messages.admin-gui-audit-player-lore", "%player%", record.username())
        )));
        inventory.setItem(22, item(Material.CLOCK, plugin.msg("messages.admin-gui-created"), List.of(
                "§f" + DATE_FORMAT.format(Instant.ofEpochMilli(record.createdAt()))
        )));

        admin.openInventory(inventory);
    }

    private void openConfirm(Player admin, UUID uuid, int returnPage, AdminGuiHolder.Action action) {
        Optional<AccountRecord> optional = store.find(uuid);
        if (optional.isEmpty()) {
            admin.closeInventory();
            admin.sendMessage(plugin.prefix() + plugin.msg("messages.admin-not-found"));
            return;
        }

        AccountRecord record = optional.get();
        AdminGuiHolder holder = new AdminGuiHolder(AdminGuiHolder.View.CONFIRM, returnPage, uuid, action);
        Inventory inventory = Bukkit.createInventory(holder, 27, plugin.msg("messages.admin-gui-confirm-title"));
        holder.inventory(inventory);

        inventory.setItem(4, accountHead(record));
        inventory.setItem(11, item(Material.LIME_CONCRETE, plugin.msg("messages.admin-gui-confirm"), List.of(
                actionLore(action, record)
        )));
        inventory.setItem(13, actionIcon(action, record));
        inventory.setItem(15, item(Material.RED_CONCRETE, plugin.msg("messages.admin-gui-cancel"), List.of()));
        admin.openInventory(inventory);
    }

    private void openAudit(Player admin, UUID target, int requestedPage, String eventFilter) {
        if (auditLogger == null) {
            admin.sendMessage(plugin.prefix() + plugin.msg("messages.admin-audit-unavailable"));
            return;
        }

        int maxEntries = Math.max(PAGE_SIZE, Math.min(500, plugin.getConfig().getInt("security.audit.viewer-max-entries", 500)));
        List<AuditEntry> entries = auditLogger.recent(target, eventFilter, maxEntries);
        int maxPage = Math.max(0, (entries.size() - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        String targetLabel = target == null
                ? "ALL"
                : store.find(target).map(AccountRecord::username).orElse(target.toString().substring(0, 8));

        AdminGuiHolder holder = new AdminGuiHolder(AdminGuiHolder.View.AUDIT, page, target, null, eventFilter);
        Inventory inventory = Bukkit.createInventory(holder, 54, plugin.msg(
                "messages.admin-audit-title",
                "%target%", targetLabel,
                "%event%", eventFilter
        ));
        holder.inventory(inventory);

        int from = page * PAGE_SIZE;
        int to = Math.min(entries.size(), from + PAGE_SIZE);
        for (int index = from; index < to; index++) {
            inventory.setItem(index - from, auditItem(entries.get(index)));
        }

        if (page > 0) {
            inventory.setItem(45, item(Material.ARROW, plugin.msg("messages.admin-gui-prev"), List.of()));
        }
        inventory.setItem(47, item(Material.ARROW, plugin.msg("messages.admin-gui-back"), List.of()));
        inventory.setItem(48, item(Material.HOPPER, plugin.msg(
                "messages.admin-audit-filter",
                "%event%", eventFilter
        ), List.of(plugin.msg("messages.admin-audit-filter-lore"))));
        inventory.setItem(49, item(Material.CLOCK, plugin.msg(
                "messages.admin-audit-summary",
                "%entries%", Integer.toString(entries.size()),
                "%page%", Integer.toString(page + 1),
                "%pages%", Integer.toString(maxPage + 1)
        ), List.of(plugin.msg("messages.admin-audit-refresh-lore"))));
        if (page < maxPage) {
            inventory.setItem(53, item(Material.ARROW, plugin.msg("messages.admin-gui-next"), List.of()));
        }

        admin.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player admin)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof AdminGuiHolder holder)) {
            return;
        }

        event.setCancelled(true);
        if (!admin.hasPermission("cdrauth.admin")) {
            admin.closeInventory();
            admin.sendMessage(plugin.prefix() + plugin.msg("messages.admin-no-permission"));
            return;
        }
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        int slot = event.getRawSlot();
        switch (holder.view()) {
            case LIST -> handleListClick(admin, holder, slot);
            case DETAIL -> handleDetailClick(admin, holder, slot);
            case CONFIRM -> handleConfirmClick(admin, holder, slot);
            case AUDIT -> handleAuditClick(admin, holder, slot);
        }
    }

    private void handleListClick(Player admin, AdminGuiHolder holder, int slot) {
        if (slot >= 0 && slot < PAGE_SIZE) {
            List<AccountRecord> accounts = store.listAll();
            int index = holder.page() * PAGE_SIZE + slot;
            if (index >= 0 && index < accounts.size()) {
                openDetail(admin, accounts.get(index).uuid(), holder.page());
            }
            return;
        }
        if (slot == 45 && holder.page() > 0) {
            openList(admin, holder.page() - 1);
        } else if (slot == 47) {
            admin.closeInventory();
            admin.sendMessage(plugin.prefix() + plugin.msg("messages.admin-gui-search-help"));
        } else if (slot == 49) {
            openList(admin, holder.page());
        } else if (slot == 51) {
            openAudit(admin, null, 0, "ALL");
        } else if (slot == 53) {
            int maxPage = Math.max(0, (store.listAll().size() - 1) / PAGE_SIZE);
            if (holder.page() < maxPage) {
                openList(admin, holder.page() + 1);
            }
        }
    }

    private void handleDetailClick(Player admin, AdminGuiHolder holder, int slot) {
        if (holder.target() == null) {
            openList(admin, holder.page());
            return;
        }
        switch (slot) {
            case 10 -> openConfirm(admin, holder.target(), holder.page(), AdminGuiHolder.Action.RESET_IP);
            case 12 -> openConfirm(admin, holder.target(), holder.page(), AdminGuiHolder.Action.RESET_PIN);
            case 16 -> openConfirm(admin, holder.target(), holder.page(), AdminGuiHolder.Action.UNREGISTER);
            case 18 -> openList(admin, holder.page());
            case 20 -> openAudit(admin, holder.target(), 0, "ALL");
            case 14 -> openDetail(admin, holder.target(), holder.page());
            default -> {
            }
        }
    }

    private void handleConfirmClick(Player admin, AdminGuiHolder holder, int slot) {
        if (holder.target() == null || holder.action() == null) {
            openList(admin, holder.page());
            return;
        }
        if (slot == 15) {
            openDetail(admin, holder.target(), holder.page());
            return;
        }
        if (slot != 11) {
            return;
        }

        String command = switch (holder.action()) {
            case RESET_IP -> "cdrauth resetip " + holder.target();
            case RESET_PIN -> "cdrauth resetpin " + holder.target();
            case UNREGISTER -> "cdrauth unreg " + holder.target();
        };

        admin.closeInventory();
        Bukkit.dispatchCommand(admin, command);
    }

    private void handleAuditClick(Player admin, AdminGuiHolder holder, int slot) {
        if (slot == 45 && holder.page() > 0) {
            openAudit(admin, holder.target(), holder.page() - 1, holder.auditEvent());
        } else if (slot == 47) {
            if (holder.target() == null) {
                openList(admin, 0);
            } else {
                openDetail(admin, holder.target(), 0);
            }
        } else if (slot == 48) {
            openAudit(admin, holder.target(), 0, nextAuditEvent(holder.target(), holder.auditEvent()));
        } else if (slot == 49) {
            openAudit(admin, holder.target(), holder.page(), holder.auditEvent());
        } else if (slot == 53) {
            int maxEntries = Math.max(PAGE_SIZE, Math.min(500, plugin.getConfig().getInt("security.audit.viewer-max-entries", 500)));
            int size = auditLogger == null ? 0 : auditLogger.recent(holder.target(), holder.auditEvent(), maxEntries).size();
            int maxPage = Math.max(0, (size - 1) / PAGE_SIZE);
            if (holder.page() < maxPage) {
                openAudit(admin, holder.target(), holder.page() + 1, holder.auditEvent());
            }
        }
    }

    private String nextAuditEvent(UUID target, String current) {
        if (auditLogger == null) {
            return "ALL";
        }
        List<String> options = new ArrayList<>();
        options.add("ALL");
        for (String event : auditLogger.recentEventTypes(target, 20)) {
            if (!event.equalsIgnoreCase("ALL") && !options.contains(event)) {
                options.add(event);
            }
        }
        int index = 0;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equalsIgnoreCase(current)) {
                index = i;
                break;
            }
        }
        return options.get((index + 1) % options.size());
    }

    private ItemStack accountHead(AccountRecord record) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        OfflinePlayer owner = Bukkit.getOfflinePlayer(record.uuid());
        meta.setOwningPlayer(owner);
        meta.setDisplayName("§b§l" + record.username());
        List<String> lore = new ArrayList<>();
        lore.add("§7UUID: §f" + record.uuid());
        lore.add("§7Player: " + (Bukkit.getPlayer(record.uuid()) == null ? "§8OFFLINE" : "§aONLINE"));
        lore.add("§7Trusted IP: " + (record.hasTrustedIp() ? "§aBOUND" : "§eRESET_PENDING"));
        lore.add("§7PIN: " + (record.pinResetRequired() ? "§eRESET_REQUIRED" : "§aACTIVE"));
        lore.add("");
        lore.add(plugin.msg("messages.admin-gui-open-detail"));
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack statusItem(AccountRecord record) {
        Player online = Bukkit.getPlayer(record.uuid());
        String session = online == null
                ? "N/A"
                : (authManager.needsAuthentication(record.uuid()) ? authManager.stage(record.uuid()).name() : "AUTHENTICATED");
        return item(Material.PAPER, plugin.msg("messages.admin-gui-status"), List.of(
                "§7Player: " + (online == null ? "§8OFFLINE" : "§aONLINE"),
                "§7Trusted IP: " + (record.hasTrustedIp() ? "§aBOUND" : "§eRESET_PENDING"),
                "§7PIN: " + (record.pinResetRequired() ? "§eRESET_REQUIRED" : "§aACTIVE"),
                "§7Session: §f" + session
        ));
    }

    private ItemStack auditItem(AuditEntry entry) {
        List<String> lore = new ArrayList<>();
        lore.add("§7Time: §f" + DATE_FORMAT.format(entry.timestamp()));
        lore.add("§7Player: §f" + entry.playerName());
        lore.add("§7UUID: §8" + entry.uuid());
        lore.add("§7IP fingerprint: §8" + entry.ipFingerprint());
        if (entry.detail() != null && !entry.detail().isBlank()) {
            lore.add("§7Detail: §f" + entry.detail());
        }
        return item(auditMaterial(entry.event()), "§b" + entry.event(), lore);
    }

    private Material auditMaterial(String event) {
        String upper = event.toUpperCase();
        if (upper.contains("SUCCESS") || upper.equals("AUTO_LOGIN")) {
            return Material.EMERALD;
        }
        if (upper.contains("WRONG") || upper.contains("LOCK") || upper.contains("BLOCK")
                || upper.contains("COLLISION") || upper.contains("TIMEOUT") || upper.contains("LIMIT")) {
            return Material.REDSTONE;
        }
        if (upper.contains("RESET") || upper.contains("CHANGE_PIN")) {
            return Material.TRIPWIRE_HOOK;
        }
        return Material.PAPER;
    }

    private ItemStack actionIcon(AdminGuiHolder.Action action, AccountRecord record) {
        return switch (action) {
            case RESET_IP -> item(Material.COMPASS, plugin.msg("messages.admin-gui-resetip"), List.of(actionLore(action, record)));
            case RESET_PIN -> item(Material.TRIPWIRE_HOOK, plugin.msg("messages.admin-gui-resetpin"), List.of(actionLore(action, record)));
            case UNREGISTER -> item(Material.BARRIER, plugin.msg("messages.admin-gui-unreg"), List.of(actionLore(action, record)));
        };
    }

    private String actionLore(AdminGuiHolder.Action action, AccountRecord record) {
        return switch (action) {
            case RESET_IP -> plugin.msg("messages.admin-gui-confirm-resetip", "%player%", record.username());
            case RESET_PIN -> plugin.msg("messages.admin-gui-confirm-resetpin", "%player%", record.username());
            case UNREGISTER -> plugin.msg("messages.admin-gui-confirm-unreg", "%player%", record.username());
        };
    }

    private ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        if (!lore.isEmpty()) {
            meta.setLore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
