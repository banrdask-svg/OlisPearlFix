package com.banan.olispearlfix;

import com.banan.olispearlfix.binding.Binding;
import com.banan.olispearlfix.binding.BlockPosition;
import java.util.List;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class StasisListener implements Listener {
    private final StasisController controller;
    private final RodIdentity rods;

    public StasisListener(StasisController controller, RodIdentity rods) {
        this.controller = controller;
        this.rods = rods;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        UUID rodId = rods.read(event.getItem());
        Binding binding = rodId == null ? null : controller.registry().byRod(rodId);
        if (binding != null) {
            event.setUseItemInHand(Event.Result.DENY);
            event.setUseInteractedBlock(Event.Result.DENY);
            controller.activate(event.getPlayer(), binding);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.FISHING) {
            EquipmentSlot hand = event.getHand();
            if (hand != null) {
                ItemStack rod = event.getPlayer().getInventory().getItem(hand);
                controller.cast(event.getPlayer(), event.getHook(), rod);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlate(EntityInteractEvent event) {
        if (event.getEntity() instanceof FishHook hook) {
            controller.bind(hook, event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRedstone(BlockRedstoneEvent event) {
        int held = controller.heldSignal(event.getBlock());
        if (held > 0) {
            event.setNewCurrent(Math.max(held, event.getNewCurrent()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        controller.leave(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        controller.join(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        controller.clearCast(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        controller.clearCast(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        controller.leave(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent event) {
        UUID rodId = rods.read(event.getBrokenItem());
        if (rodId != null) {
            controller.rodDestroyed(rodId);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(EntityRemoveEvent event) {
        if (event.getEntity() instanceof EnderPearl pearl) {
            controller.rememberPearl(pearl);
        }
        if (event.getCause() == EntityRemoveEvent.Cause.UNLOAD
                || event.getCause() == EntityRemoveEvent.Cause.PLAYER_QUIT) {
            return;
        }
        controller.entityRemoved(event.getEntity().getUniqueId(), event.getEntity() instanceof EnderPearl);
        if (event.getEntity() instanceof Item item && isDestruction(event.getCause())) {
            UUID id = rods.read(item.getItemStack());
            if (id != null) {
                controller.rodDestroyed(id);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        controller.restore(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) {
        controller.unload(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (var entity : event.getEntities()) {
            if (entity instanceof EnderPearl pearl) {
                controller.rememberPearl(pearl);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        controller.onWorldLoad(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        controller.onWorldUnload(event.getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Binding binding = protectedBinding(event.getBlock());
        if (binding != null && binding.holdsPlate()) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§eСначала отмените привязку: /stasis cancel " + binding.id());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluid(BlockFromToEvent event) {
        if (protectedBinding(event.getToBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        Binding binding = controller.registry().byPlate(StasisController.position(event.getBlock()));
        if (binding != null && binding.holdsPlate()) {
            // Protect against support removal; native scheduled pressure-plate ticks still run.
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        protectExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) {
        protectExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> protectedBinding(block) != null
                || protectedBinding(block.getRelative(event.getDirection())) != null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> protectedBinding(block) != null
                || protectedBinding(block.getRelative(event.getDirection())) != null)) {
            event.setCancelled(true);
        }
    }

    private Binding protectedBinding(Block block) {
        BlockPosition position = StasisController.position(block);
        Binding binding = controller.registry().byPlate(position);
        if (binding == null) {
            binding = controller.registry().byPlate(new BlockPosition(position.worldId(), position.x(), position.y() + 1, position.z()));
        }
        return binding != null && binding.holdsPlate() ? binding : null;
    }

    private void protectExplosion(List<Block> blocks) {
        blocks.removeIf(block -> protectedBinding(block) != null);
    }

    private static boolean isDestruction(EntityRemoveEvent.Cause cause) {
        return switch (cause) {
            case DEATH, DESPAWN, EXPLODE, OUT_OF_WORLD, PLUGIN, DISCARD -> true;
            default -> false;
        };
    }
}
