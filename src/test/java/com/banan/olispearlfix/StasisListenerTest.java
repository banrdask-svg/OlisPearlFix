package com.banan.olispearlfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.banan.olispearlfix.binding.Binding;
import com.banan.olispearlfix.binding.BindingRegistry;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StasisListenerTest {
    private final StasisController controller = mock(StasisController.class);
    private final RodIdentity rods = mock(RodIdentity.class);
    private final BindingRegistry registry = new BindingRegistry();
    private StasisListener listener;

    @BeforeEach
    void setUp() {
        when(controller.registry()).thenReturn(registry);
        listener = new StasisListener(controller, rods);
    }

    @Test
    void quittingAndUnloadingPearlsDoNotDeleteBindings() {
        EnderPearl pearl = mock(EnderPearl.class);
        when(pearl.getUniqueId()).thenReturn(UUID.randomUUID());
        listener.onRemove(new EntityRemoveEvent(pearl, EntityRemoveEvent.Cause.PLAYER_QUIT));
        listener.onRemove(new EntityRemoveEvent(pearl, EntityRemoveEvent.Cause.UNLOAD));
        verify(controller, never()).entityRemoved(any(), eq(true));
        listener.onRemove(new EntityRemoveEvent(pearl, EntityRemoveEvent.Cause.HIT));
        verify(controller).entityRemoved(pearl.getUniqueId(), true);
    }

    @Test
    void disappearingHookDoesNotRemoveItsPersistentBinding() {
        FishHook hook = mock(FishHook.class);
        UUID id = UUID.randomUUID();
        when(hook.getUniqueId()).thenReturn(id);
        listener.onRemove(new EntityRemoveEvent(hook, EntityRemoveEvent.Cause.DESPAWN));
        verify(controller).entityRemoved(id, false);
        verify(controller, never()).rodDestroyed(any());
    }

    @Test
    void nativeReleaseIsSuppressedOnlyForHeldPlates() {
        Block block = mock(Block.class);
        when(controller.heldSignal(block)).thenReturn(15);
        BlockRedstoneEvent held = new BlockRedstoneEvent(block, 15, 0);
        listener.onRedstone(held);
        assertEquals(15, held.getNewCurrent());
        when(controller.heldSignal(block)).thenReturn(0);
        BlockRedstoneEvent released = new BlockRedstoneEvent(block, 15, 0);
        listener.onRedstone(released);
        assertEquals(0, released.getNewCurrent());
    }

    @Test
    void ordinaryFishingIsNotCancelled() {
        Player player = mock(Player.class);
        PlayerInteractEvent event = use(player, mock(ItemStack.class));
        listener.onUse(event);
        assertEquals(Event.Result.DEFAULT, event.useItemInHand());
        verify(controller, never()).activate(any(), any());
    }

    @Test
    void boundRodUsesSameIdentityInEitherHandIncludingTransferredRod() {
        Player holder = mock(Player.class);
        Binding binding = Fixtures.binding(UUID.randomUUID(), 0);
        registry.add(binding, 8);
        ItemStack rod = mock(ItemStack.class);
        when(rod.getType()).thenReturn(Material.FISHING_ROD);
        when(rods.read(rod)).thenReturn(binding.rodId());
        PlayerInteractEvent event = use(holder, rod);
        listener.onUse(event);
        assertEquals(Event.Result.DENY, event.useItemInHand());
        verify(controller).activate(holder, binding);
    }

    @Test
    void quitCancelsPendingRequestsWithoutDroppingRegistryEntry() {
        Player player = mock(Player.class);
        UUID owner = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(owner);
        Binding binding = Fixtures.binding(owner, 0);
        registry.add(binding, 8);
        listener.onQuit(new PlayerQuitEvent(player, net.kyori.adventure.text.Component.empty(),
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertEquals(binding, registry.byRod(binding.rodId()));
        verify(controller).leave(owner);
    }

    private static PlayerInteractEvent use(Player player, ItemStack rod) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, rod, null, null, EquipmentSlot.OFF_HAND);
    }
}
