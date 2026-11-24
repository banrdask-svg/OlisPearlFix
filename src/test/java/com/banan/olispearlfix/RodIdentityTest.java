package com.banan.olispearlfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class RodIdentityTest {
    @Test
    void persistentIdentitySurvivesReadAndRotatesForNewCast() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.namespace()).thenReturn("olispearlfix");
        RodIdentity identity = new RodIdentity(plugin);
        ItemStack rod = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(rod.getType()).thenReturn(Material.FISHING_ROD);
        when(rod.hasItemMeta()).thenReturn(true);
        when(rod.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        AtomicReference<String> stored = new AtomicReference<>();
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(2, String.class));
            return null;
        }).when(data).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), any(String.class));
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(ignored -> stored.get());
        UUID first = identity.renew(rod);
        assertEquals(first, identity.read(rod));
        assertEquals(first, identity.read(rod));
        UUID next = identity.renew(rod);
        assertNotEquals(first, next);
        assertEquals(next, identity.read(rod));
        stored.set("invalid-UUID");
        assertNull(identity.read(rod));
        assertNull(identity.read(null));
    }
}
