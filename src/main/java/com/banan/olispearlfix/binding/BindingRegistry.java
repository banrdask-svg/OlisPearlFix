package com.banan.olispearlfix.binding;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BindingRegistry {
    private final Map<UUID, Binding> bindings = new LinkedHashMap<>();
    private final Map<UUID, UUID> rods = new HashMap<>();
    private final Map<UUID, UUID> pearls = new HashMap<>();
    private final Map<BlockPosition, UUID> plates = new HashMap<>();
    private final Map<UUID, Set<UUID>> owners = new HashMap<>();
    private final Map<ChunkPosition, Set<UUID>> chunks = new HashMap<>();

    public void add(Binding binding, int ownerLimit) {
        if (bindings.containsKey(binding.id()) || rods.containsKey(binding.rodId())
                || pearls.containsKey(binding.pearlId()) || plates.containsKey(binding.plate())) {
            throw new IllegalArgumentException("Duplicate binding, rod, pearl or plate");
        }
        if (forOwner(binding.ownerId()).size() >= ownerLimit) {
            throw new IllegalArgumentException("Owner chamber limit reached");
        }
        bindings.put(binding.id(), binding);
        rods.put(binding.rodId(), binding.id());
        pearls.put(binding.pearlId(), binding.id());
        plates.put(binding.plate(), binding.id());
        owners.computeIfAbsent(binding.ownerId(), ignored -> new LinkedHashSet<>()).add(binding.id());
        chunks.computeIfAbsent(binding.plate().chunk(), ignored -> new LinkedHashSet<>()).add(binding.id());
    }

    public Binding get(UUID id) {
        return bindings.get(id);
    }

    public Binding byRod(UUID id) {
        return get(rods.get(id));
    }

    public Binding byPearl(UUID id) {
        return get(pearls.get(id));
    }

    public Binding byPlate(BlockPosition position) {
        return get(plates.get(position));
    }

    public List<Binding> forOwner(UUID owner) {
        return resolve(owners.getOrDefault(owner, Set.of()));
    }

    public List<Binding> inChunk(ChunkPosition chunk) {
        return resolve(chunks.getOrDefault(chunk, Set.of()));
    }

    public Collection<Binding> all() {
        return List.copyOf(bindings.values());
    }

    public void update(Binding binding) {
        Binding previous = get(binding.id());
        if (previous == null || !previous.withState(binding.state()).withPearlPosition(binding.pearlPosition()).equals(binding)) {
            throw new IllegalArgumentException("Only binding state and pearl position may change");
        }
        bindings.put(binding.id(), binding);
    }

    public Binding remove(UUID id) {
        Binding removed = bindings.remove(id);
        if (removed != null) {
            rods.remove(removed.rodId());
            pearls.remove(removed.pearlId());
            plates.remove(removed.plate());
            removeIndex(owners, removed.ownerId(), id);
            removeIndex(chunks, removed.plate().chunk(), id);
        }
        return removed;
    }

    private List<Binding> resolve(Set<UUID> ids) {
        return ids.stream().map(bindings::get).toList();
    }

    private static <K> void removeIndex(Map<K, Set<UUID>> index, K key, UUID id) {
        Set<UUID> values = index.get(key);
        values.remove(id);
        if (values.isEmpty()) {
            index.remove(key);
        }
    }
}
