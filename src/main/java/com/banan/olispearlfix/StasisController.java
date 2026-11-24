package com.banan.olispearlfix;

import com.banan.olispearlfix.binding.Binding;
import com.banan.olispearlfix.binding.BindingRegistry;
import com.banan.olispearlfix.binding.BlockPosition;
import com.banan.olispearlfix.binding.ChunkPosition;
import com.banan.olispearlfix.persistence.BindingStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class StasisController {
    private final JavaPlugin plugin;
    private final PluginSettings settings;
    private final BindingStore store;
    private final RodIdentity rods;
    private final BindingRegistry registry = new BindingRegistry();
    private final Map<UUID, Cast> casts = new HashMap<>();
    private final Map<UUID, UUID> playerHooks = new HashMap<>();
    private final Map<UUID, Operation> operations = new HashMap<>();
    private final Map<ChunkPosition, Integer> tickets = new HashMap<>();
    private volatile boolean stopping;

    public StasisController(JavaPlugin plugin, PluginSettings settings, BindingStore store, RodIdentity rods) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = store;
        this.rods = rods;
    }

    public void load() throws IOException {
        for (Binding binding : store.load()) {
            try {
                registry.add(binding, Integer.MAX_VALUE);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Conflicting saved binding: " + binding.id(), exception);
            }
        }
    }

    public BindingRegistry registry() {
        return registry;
    }

    public void cast(Player player, FishHook hook, ItemStack rod) {
        clearCast(player.getUniqueId());
        UUID rodId = rods.renew(rod);
        casts.put(hook.getUniqueId(), new Cast(player.getUniqueId(), rodId));
        playerHooks.put(player.getUniqueId(), hook.getUniqueId());
    }

    public void bind(FishHook hook, Block plate) {
        Cast cast = casts.get(hook.getUniqueId());
        if (cast == null || !isSupportedPlate(plate.getType()) || registry.byPlate(position(plate)) != null
                || registry.byRod(cast.rodId()) != null) {
            return;
        }
        Player owner = Bukkit.getPlayer(cast.ownerId());
        if (owner == null || registry.forOwner(cast.ownerId()).size() >= settings.ownerLimit()) {
            return;
        }
        EnderPearl pearl = findPearl(owner, plate);
        if (pearl == null) {
            return;
        }
        Location location = hook.getLocation();
        Binding binding = new Binding(UUID.randomUUID(), cast.ownerId(), cast.rodId(), position(plate),
                plate.getWorld().getName(), plate.getType().name(),
                plate.getType() == Material.HEAVY_WEIGHTED_PRESSURE_PLATE
                        || plate.getType() == Material.LIGHT_WEIGHTED_PRESSURE_PLATE ? 1 : 15,
                pearl.getUniqueId(), position(pearl.getLocation()), hook.getUniqueId(),
                location.getX(), location.getY(), location.getZ(), Binding.State.HELD);
        try {
            if (settings.persistent()) {
                store.save(binding);
            }
            registry.add(binding, settings.ownerLimit());
            debug("Bound " + binding.id());
            owner.sendMessage("§aСтазис-камера привязана к этой удочке.");
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not persist stasis binding; binding was not created", exception);
            owner.sendMessage("§cНе удалось сохранить камеру. Проверьте журнал сервера.");
        }
    }

    public int heldSignal(Block block) {
        Binding binding = registry.byPlate(position(block));
        return binding != null && binding.holdsPlate() && matches(binding, block) ? binding.signal() : 0;
    }

    public void activate(Player user, Binding binding) {
        if (binding.state() != Binding.State.HELD || operations.containsKey(binding.id())) {
            return;
        }
        Player owner = Bukkit.getPlayer(binding.ownerId());
        if (owner == null || owner.isDead()) {
            user.sendMessage("§eВладелец жемчуга должен быть на сервере и жив.");
            return;
        }
        request(binding, user.getUniqueId(), false);
    }

    public void disarm(Binding binding, Player user) {
        if (binding.state() == Binding.State.RELEASED || operations.containsKey(binding.id())) {
            return;
        }
        Binding pending = binding.withState(Binding.State.DISARMING);
        if (!persistState(pending)) {
            return;
        }
        if (Bukkit.getPlayer(binding.ownerId()) == null) {
            if (user != null) {
                user.sendMessage("§eОтмена сохранена и завершится после входа владельца жемчуга.");
            }
            return;
        }
        request(pending, user == null ? null : user.getUniqueId(), true);
    }

    public void rodDestroyed(UUID rodId) {
        Binding binding = registry.byRod(rodId);
        if (binding != null) {
            disarm(binding, null);
        }
    }

    public void entityRemoved(UUID id, boolean pearl) {
        casts.remove(id);
        if (pearl) {
            Binding binding = registry.byPearl(id);
            if (binding != null) {
                // EntityRemoveEvent is monitoring only; defer registry changes and chunk access.
                Bukkit.getScheduler().runTask(plugin, () -> forget(binding.id()));
            }
        }
    }

    public void rememberPearl(EnderPearl pearl) {
        Binding binding = registry.byPearl(pearl.getUniqueId());
        if (binding != null) {
            BlockPosition location = position(pearl.getLocation());
            if (location.worldId().equals(binding.plate().worldId()) && !location.equals(binding.pearlPosition())) {
                persistState(binding.withPearlPosition(location));
            }
        }
    }

    public void join(UUID playerId) {
        clearCast(playerId);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Binding binding : registry.forOwner(playerId)) {
                if (binding.state() == Binding.State.DISARMING) {
                    request(binding, playerId, true);
                }
            }
        });
    }

    public void unload(Chunk chunk) {
        // Block states and pending native ticks are saved by Minecraft itself.
        // Bindings already have durable per-record commits; no tickets are added here.
        debug("Unloaded chamber chunk " + chunk.getX() + "," + chunk.getZ()
                + " (" + registry.inChunk(new ChunkPosition(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ())).size() + " bindings)");
    }

    public void leave(UUID playerId) {
        clearCast(playerId);
        for (Operation operation : List.copyOf(operations.values())) {
            Binding binding = registry.get(operation.bindingId);
            if (!operation.disarming && binding != null
                    && (binding.ownerId().equals(playerId) || playerId.equals(operation.userId))) {
                cancelBeforePlateRelease(operation, binding);
            }
        }
    }

    public void clearCast(UUID playerId) {
        UUID hookId = playerHooks.remove(playerId);
        if (hookId != null) {
            casts.remove(hookId);
        }
    }

    public void restore(Chunk chunk) {
        for (Binding binding : registry.inChunk(new ChunkPosition(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()))) {
            Block plate = chunk.getWorld().getBlockAt(binding.plate().x(), binding.plate().y(), binding.plate().z());
            if (binding.state() == Binding.State.RELEASED) {
                forget(binding.id());
            } else if (!matches(binding, plate)) {
                disarm(binding, null);
            } else if (binding.state() == Binding.State.DISARMING) {
                request(binding, null, true);
            }
            // Powered block states and their native scheduled ticks persist with the chunk.
            // Never toggle a held plate during load: that would pulse its redstone circuit.
        }
    }

    public void onWorldLoad(World world) {
        for (Chunk chunk : world.getLoadedChunks()) {
            restore(chunk);
        }
    }

    public void onWorldUnload(World world) {
        for (Operation operation : List.copyOf(operations.values())) {
            Binding binding = registry.get(operation.bindingId);
            if (binding != null && binding.plate().worldId().equals(world.getUID())) {
                if (operation.disarming) {
                    finish(operation);
                } else {
                    cancelBeforePlateRelease(operation, binding);
                    finish(operation);
                }
            }
        }
    }

    public void saveAll() {
        if (!settings.persistent()) {
            return;
        }
        for (Binding binding : registry.all()) {
            try {
                store.save(binding);
            } catch (IOException exception) {
                plugin.getLogger().log(Level.SEVERE, "Could not save binding " + binding.id(), exception);
                return;
            }
        }
    }

    public void shutdown() {
        stopping = true;
        for (Operation operation : List.copyOf(operations.values())) {
            finish(operation);
        }
        for (World world : Bukkit.getWorlds()) {
            world.removePluginChunkTickets(plugin);
        }
        tickets.clear();
        casts.clear();
        playerHooks.clear();
        saveAll();
    }

    private void request(Binding binding, UUID userId, boolean disarming) {
        if (stopping || operations.containsKey(binding.id())) {
            return;
        }
        if (disarming && Bukkit.getPlayer(binding.ownerId()) == null) {
            return;
        }
        World world = Bukkit.getWorld(binding.plate().worldId());
        if (world == null) {
            tell(userId, "§eМир камеры недоступен; привязка сохранена.");
            return;
        }
        if (operations.size() >= settings.maxOperations()) {
            tell(userId, "§eСервер занят активацией камер. Повторите попытку.");
            return;
        }
        Operation operation = new Operation(binding.id(), userId, disarming);
        operations.put(binding.id(), operation);
        operation.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (operations.get(binding.id()) == operation) {
                tell(userId, "§eЗагрузка камеры не завершилась; привязка сохранена.");
                finish(operation);
            }
        }, settings.timeoutTicks());
        Set<ChunkPosition> required = new HashSet<>();
        addNeighborhood(required, binding.plate().chunk());
        addNeighborhood(required, binding.pearlPosition().chunk());
        List<CompletableFuture<Chunk>> futures = new ArrayList<>();
        for (ChunkPosition key : required) {
            CompletableFuture<Chunk> future = world.getChunkAtAsync(key.x(), key.z(), false);
            futures.add(future);
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture<?>[]::new)).whenComplete((ignored, error) -> {
            if (stopping) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (operations.get(binding.id()) != operation) {
                    return;
                }
                if (Bukkit.getWorld(binding.plate().worldId()) != world) {
                    finish(operation);
                    return;
                }
                if (error != null || futures.stream().anyMatch(future -> future.join() == null)) {
                    plugin.getLogger().log(Level.WARNING, "Could not load chunks for binding " + binding.id(), error);
                    tell(userId, "§eНе удалось загрузить камеру; привязка сохранена.");
                    finish(operation);
                    return;
                }
                for (ChunkPosition key : required) {
                    acquireTicket(world, key);
                    operation.chunks.add(key);
                }
                awaitEntities(operation, world);
            });
        });
    }

    private void awaitEntities(Operation operation, World world) {
        if (operations.get(operation.bindingId) != operation) {
            return;
        }
        for (ChunkPosition chunk : operation.chunks) {
            if (!world.getChunkAt(chunk.x(), chunk.z()).isEntitiesLoaded()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> awaitEntities(operation, world), 1);
                return;
            }
        }
        commit(operation, world);
    }

    private void commit(Operation operation, World world) {
        Binding binding = registry.get(operation.bindingId);
        if (binding == null) {
            finish(operation);
            return;
        }
        Entity entity = world.getEntity(binding.pearlId());
        if (operation.disarming) {
            if (Bukkit.getPlayer(binding.ownerId()) == null) {
                finish(operation);
                return;
            }
            if (entity instanceof EnderPearl) {
                entity.remove();
            }
        } else {
            Player owner = Bukkit.getPlayer(binding.ownerId());
            Player user = Bukkit.getPlayer(operation.userId);
            Block plate = world.getBlockAt(binding.plate().x(), binding.plate().y(), binding.plate().z());
            if (owner == null || owner.isDead() || user == null) {
                finish(operation);
                return;
            }
            if (!(entity instanceof EnderPearl pearl) || !entity.isValid()) {
                tell(operation.userId, "§eЖемчуг отсутствует. Привязка камеры удалена.");
                forget(binding.id());
                finish(operation);
                return;
            }
            if (!matches(binding, plate)) {
                pearl.remove();
            } else {
                pearl.setShooter(owner);
            }
        }
        Binding released = binding.withState(Binding.State.RELEASED);
        if (!persistState(released)) {
            finish(operation);
            return;
        }
        operation.committed = true;
        Entity hook = world.getEntity(binding.hookId());
        if (hook instanceof FishHook) {
            hook.remove();
        }
        debug("Released " + binding.id() + (operation.disarming ? " safely" : " through native pressure plate tick"));
        tell(operation.userId, operation.disarming ? "§aКамера отменена; жемчуг удалён без телепортации."
                : "§aПривязка снята. Камеру активирует обычный редстоун.");
        operation.timeout.cancel();
        operation.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            forget(binding.id());
            finish(operation);
        }, settings.ticketTicks());
    }

    private boolean persistState(Binding binding) {
        try {
            if (settings.persistent()) {
                store.save(binding);
            } else {
                store.delete(binding.id());
            }
            registry.update(binding);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not change persisted binding " + binding.id(), exception);
            return false;
        }
    }

    private void forget(UUID id) {
        try {
            store.delete(id);
            registry.remove(id);
        } catch (IOException exception) {
            plugin.getLogger().log(Level.SEVERE, "Could not remove persisted binding " + id, exception);
        }
    }

    private void acquireTicket(World world, ChunkPosition key) {
        int count = tickets.getOrDefault(key, 0);
        if (count == 0) {
            world.addPluginChunkTicket(key.x(), key.z(), plugin);
        }
        tickets.put(key, count + 1);
    }

    private void cancelBeforePlateRelease(Operation operation, Binding binding) {
        if (!operation.committed) {
            finish(operation);
            return;
        }
        World world = Bukkit.getWorld(binding.plate().worldId());
        if (world != null && world.isChunkLoaded(binding.plate().chunk().x(), binding.plate().chunk().z())) {
            Block block = world.getBlockAt(binding.plate().x(), binding.plate().y(), binding.plate().z());
            if (matches(binding, block) && signal(block) > 0
                    && persistState(binding.withState(Binding.State.HELD))) {
                finish(operation);
            }
        }
    }

    private void finish(Operation operation) {
        if (!operations.remove(operation.bindingId, operation)) {
            return;
        }
        if (operation.timeout != null) {
            operation.timeout.cancel();
        }
        for (ChunkPosition key : operation.chunks) {
            Integer count = tickets.get(key);
            if (count != null && count == 1) {
                World world = Bukkit.getWorld(key.worldId());
                if (world != null) {
                    world.removePluginChunkTicket(key.x(), key.z(), plugin);
                }
                tickets.remove(key);
            } else if (count != null) {
                tickets.put(key, count - 1);
            }
        }
    }

    private EnderPearl findPearl(Player player, Block plate) {
        Location center = plate.getLocation().add(0.5, 0.5, 0.5);
        EnderPearl nearest = null;
        double distance = Double.MAX_VALUE;
        int radius = settings.pearlRadius();
        for (Entity entity : plate.getWorld().getNearbyEntities(center, radius, radius * 2.0, radius,
                candidate -> candidate instanceof EnderPearl)) {
            EnderPearl pearl = (EnderPearl) entity;
            if (pearl.getShooter() instanceof Player owner && owner.getUniqueId().equals(player.getUniqueId())
                    && registry.byPearl(pearl.getUniqueId()) == null) {
                double candidateDistance = pearl.getLocation().distanceSquared(center);
                if (candidateDistance < distance) {
                    nearest = pearl;
                    distance = candidateDistance;
                }
            }
        }
        return nearest;
    }

    private void tell(UUID playerId, String message) {
        Player player = playerId == null ? null : Bukkit.getPlayer(playerId);
        if (player != null) {
            player.sendMessage(message);
        }
    }

    private void debug(String message) {
        if (settings.debug()) {
            plugin.getLogger().info(message);
        }
    }

    public static boolean isSupportedPlate(Material material) {
        return Tag.WOODEN_PRESSURE_PLATES.isTagged(material)
                || material == Material.LIGHT_WEIGHTED_PRESSURE_PLATE
                || material == Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
    }

    public static int signal(Block block) {
        if (block.getBlockData() instanceof Powerable powerable) {
            return powerable.isPowered() ? 15 : 0;
        }
        if (block.getBlockData() instanceof AnaloguePowerable analogue) {
            return analogue.getPower();
        }
        return 0;
    }

    public static BlockPosition position(Block block) {
        return new BlockPosition(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }

    public static BlockPosition position(Location location) {
        return new BlockPosition(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    private static boolean matches(Binding binding, Block block) {
        return block.getType().name().equals(binding.plateMaterial());
    }

    private static void addNeighborhood(Set<ChunkPosition> result, ChunkPosition center) {
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                result.add(new ChunkPosition(center.worldId(), center.x() + x, center.z() + z));
            }
        }
    }

    private record Cast(UUID ownerId, UUID rodId) {
    }

    private static final class Operation {
        private final UUID bindingId;
        private final UUID userId;
        private final boolean disarming;
        private final Set<ChunkPosition> chunks = new HashSet<>();
        private BukkitTask timeout;
        private boolean committed;

        private Operation(UUID bindingId, UUID userId, boolean disarming) {
            this.bindingId = bindingId;
            this.userId = userId;
            this.disarming = disarming;
        }
    }
}
