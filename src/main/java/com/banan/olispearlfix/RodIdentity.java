package com.banan.olispearlfix;

import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public final class RodIdentity {
    private final NamespacedKey key;

    public RodIdentity(Plugin plugin) {
        key = new NamespacedKey(plugin, "rod_id");
    }

    public UUID read(ItemStack item) {
        if (item == null || item.getType() != Material.FISHING_ROD || !item.hasItemMeta()) {
            return null;
        }
        String value = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            // Invalid item metadata is untrusted input; it must never address a chamber.
            return null;
        }
    }

    public UUID renew(ItemStack item) {
        UUID id = UUID.randomUUID();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, id.toString());
        item.setItemMeta(meta);
        return id;
    }
}
