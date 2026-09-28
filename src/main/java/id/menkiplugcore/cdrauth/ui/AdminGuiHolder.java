package id.menkiplugcore.cdrauth.ui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

final class AdminGuiHolder implements InventoryHolder {
    enum View {
        LIST,
        DETAIL,
        CONFIRM
    }

    enum Action {
        RESET_IP,
        RESET_PIN,
        UNREGISTER
    }

    private final View view;
    private final int page;
    private final UUID target;
    private final Action action;
    private Inventory inventory;

    AdminGuiHolder(View view, int page, UUID target, Action action) {
        this.view = view;
        this.page = page;
        this.target = target;
        this.action = action;
    }

    View view() {
        return view;
    }

    int page() {
        return page;
    }

    UUID target() {
        return target;
    }

    Action action() {
        return action;
    }

    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }
}
