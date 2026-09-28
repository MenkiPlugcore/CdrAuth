package id.menkiplugcore.cdrauth.ui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public final class PinGuiHolder implements InventoryHolder {
    private final UUID owner;
    private Inventory inventory;

    public PinGuiHolder(UUID owner) {
        this.owner = owner;
    }

    public UUID owner() {
        return owner;
    }

    public void inventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            throw new IllegalStateException("Inventory not initialized");
        }
        return inventory;
    }
}
