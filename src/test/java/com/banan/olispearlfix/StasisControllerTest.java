package com.banan.olispearlfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.banan.olispearlfix.binding.Binding;
import com.banan.olispearlfix.persistence.BindingStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

class StasisControllerTest {
    @TempDir
    Path directory;
    private MockedStatic<Bukkit> bukkit;
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final World world = mock(World.class);
    private final Player owner = mock(Player.class);
    private final Chunk chunk = mock(Chunk.class);
    private final EnderPearl pearl = mock(EnderPearl.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final ArrayDeque<Runnable> immediate = new ArrayDeque<>();
    private final List<Runnable> delayed = new ArrayList<>();
    private final CompletableFuture<Chunk> loading = new CompletableFuture<>();
    private Binding binding;
    private BindingStore store;
    private StasisController controller;

    @BeforeEach
    void setUp() throws IOException {
        binding = Fixtures.binding(UUID.randomUUID(), 0);
        store = new BindingStore(directory);
        store.save(binding);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("stasis-tests"));
        when(owner.getUniqueId()).thenReturn(binding.ownerId());
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayer(binding.ownerId())).thenReturn(owner);
        bukkit.when(() -> Bukkit.getWorld(binding.plate().worldId())).thenReturn(world);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(world.getChunkAtAsync(anyInt(), anyInt(), eq(false))).thenReturn(loading);
        when(world.getChunkAt(anyInt(), anyInt())).thenReturn(chunk);
        when(chunk.isEntitiesLoaded()).thenReturn(true);
        when(world.getEntity(binding.pearlId())).thenReturn(pearl);
        when(pearl.isValid()).thenReturn(true);
        Block plate = mock(Block.class);
        when(plate.getType()).thenReturn(Material.OAK_PRESSURE_PLATE);
        when(world.getBlockAt(binding.plate().x(), binding.plate().y(), binding.plate().z())).thenReturn(plate);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            immediate.add(invocation.getArgument(1, Runnable.class));
            return mock(BukkitTask.class);
        });
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong())).thenAnswer(invocation -> {
            delayed.add(invocation.getArgument(1, Runnable.class));
            return mock(BukkitTask.class);
        });
        controller = new StasisController(plugin, new PluginSettings(true, 8, true, 60, false, 8, 200, 100, 8),
                store, mock(RodIdentity.class));
        controller.load();
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    @Test
    void repeatedUsesQueueExactlyOneActivationAndOnlyReleaseAfterEntitiesLoad() throws IOException {
        controller.activate(owner, binding);
        controller.activate(owner, binding);
        verify(world, times(9)).getChunkAtAsync(anyInt(), anyInt(), eq(false));
        assertTrue(controller.registry().get(binding.id()).holdsPlate());
        loading.complete(chunk);
        runImmediate();
        assertFalse(controller.registry().get(binding.id()).holdsPlate());
        assertEquals(Binding.State.RELEASED, store.load().getFirst().state());
        verify(pearl).setShooter(owner);
        verify(world, times(9)).addPluginChunkTicket(anyInt(), anyInt(), eq(plugin));
        delayed.getLast().run();
        assertTrue(controller.registry().all().isEmpty());
        verify(world, times(9)).removePluginChunkTicket(anyInt(), anyInt(), eq(plugin));
    }

    @Test
    void logoutDuringLoadingRetainsBindingAndRejectsLateCompletion() {
        controller.activate(owner, binding);
        controller.leave(binding.ownerId());
        loading.complete(chunk);
        runImmediate();
        assertTrue(controller.registry().get(binding.id()).holdsPlate());
        verify(pearl, never()).setShooter(any());
        verify(world, never()).addPluginChunkTicket(anyInt(), anyInt(), any());
    }

    @Test
    void timeoutRetainsBindingAndRejectsLateCompletion() {
        controller.activate(owner, binding);
        delayed.getFirst().run();
        loading.complete(chunk);
        runImmediate();
        assertTrue(controller.registry().get(binding.id()).holdsPlate());
        verify(world, never()).addPluginChunkTicket(anyInt(), anyInt(), any());
    }

    @Test
    void offlineOwnerCannotBeActivatedOrLosePlayerSavedPearl() throws IOException {
        bukkit.when(() -> Bukkit.getPlayer(binding.ownerId())).thenReturn(null);
        controller.activate(owner, binding);
        verify(world, never()).getChunkAtAsync(anyInt(), anyInt(), eq(false));
        controller.disarm(binding, owner);
        assertEquals(Binding.State.DISARMING, store.load().getFirst().state());
        assertTrue(controller.registry().get(binding.id()).holdsPlate());
        verify(pearl, never()).remove();
    }

    @Test
    void disarmRemovesRealPearlBeforeReleasingPlate() {
        controller.disarm(binding, owner);
        assertTrue(controller.registry().get(binding.id()).holdsPlate());
        loading.complete(chunk);
        runImmediate();
        verify(pearl).remove();
        assertFalse(controller.registry().get(binding.id()).holdsPlate());
        verify(pearl, never()).setShooter(any());
    }

    @Test
    void logoutAfterCommitDoesNotLeakTicketsOrReleasedRecord() {
        controller.activate(owner, binding);
        loading.complete(chunk);
        runImmediate();
        controller.leave(binding.ownerId());
        delayed.getLast().run();
        assertTrue(controller.registry().all().isEmpty());
        verify(world, times(9)).removePluginChunkTicket(anyInt(), anyInt(), eq(plugin));
    }

    @Test
    void logoutAfterCommitButBeforeNativeReleaseRearmsSameBinding() throws IOException {
        controller.activate(owner, binding);
        loading.complete(chunk);
        runImmediate();
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        Block plate = world.getBlockAt(binding.plate().x(), binding.plate().y(), binding.plate().z());
        Powerable data = mock(Powerable.class);
        when(data.isPowered()).thenReturn(true);
        when(plate.getBlockData()).thenReturn(data);
        controller.leave(binding.ownerId());
        assertEquals(Binding.State.HELD, controller.registry().get(binding.id()).state());
        assertEquals(Binding.State.HELD, store.load().getFirst().state());
        verify(world, times(9)).removePluginChunkTicket(anyInt(), anyInt(), eq(plugin));
    }

    @Test
    void unloadingWorldCancelsPendingRequestsAndRejectsLateCompletion() {
        when(world.getUID()).thenReturn(binding.plate().worldId());
        controller.activate(owner, binding);
        controller.onWorldUnload(world);
        loading.complete(chunk);
        runImmediate();
        assertTrue(controller.registry().get(binding.id()).holdsPlate());
        verify(world, never()).addPluginChunkTicket(anyInt(), anyInt(), any());
    }

    @Test
    void sharedChunksRetainTicketsUntilBothOperationsComplete() throws IOException {
        Binding second = Fixtures.binding(binding.ownerId(), 1);
        store.save(second);
        controller.registry().add(second, 8);
        EnderPearl secondPearl = mock(EnderPearl.class);
        when(secondPearl.isValid()).thenReturn(true);
        when(world.getEntity(second.pearlId())).thenReturn(secondPearl);
        Block secondPlate = mock(Block.class);
        when(secondPlate.getType()).thenReturn(Material.OAK_PRESSURE_PLATE);
        when(world.getBlockAt(second.plate().x(), second.plate().y(), second.plate().z())).thenReturn(secondPlate);
        controller.activate(owner, binding);
        controller.activate(owner, second);
        loading.complete(chunk);
        runImmediate();
        verify(world, times(9)).addPluginChunkTicket(anyInt(), anyInt(), eq(plugin));
        delayed.get(2).run();
        verify(world, never()).removePluginChunkTicket(anyInt(), anyInt(), eq(plugin));
        delayed.get(3).run();
        verify(world, times(9)).removePluginChunkTicket(anyInt(), anyInt(), eq(plugin));
    }

    @Test
    void unloadedEntitiesDelayReleaseAndPreserveHeldState() {
        when(chunk.isEntitiesLoaded()).thenReturn(false);
        controller.activate(owner, binding);
        loading.complete(chunk);
        runImmediate();
        assertTrue(controller.registry().get(binding.id()).holdsPlate());
        verify(pearl, never()).setShooter(any());
        when(chunk.isEntitiesLoaded()).thenReturn(true);
        delayed.getLast().run();
        assertFalse(controller.registry().get(binding.id()).holdsPlate());
    }

    private void runImmediate() {
        while (!immediate.isEmpty()) {
            immediate.removeFirst().run();
        }
    }
}
