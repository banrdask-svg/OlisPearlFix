package com.banan.olispearlfix.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.banan.olispearlfix.Fixtures;
import com.banan.olispearlfix.binding.Binding;
import com.banan.olispearlfix.binding.BindingRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BindingStoreTest {
    @TempDir
    Path directory;

    @Test
    void restartRestoresAllIdsLocationsAndStates() throws IOException {
        BindingStore original = new BindingStore(directory);
        Binding first = Fixtures.binding(UUID.randomUUID(), -17);
        Binding second = Fixtures.binding(first.ownerId(), 32).withState(Binding.State.DISARMING);
        original.save(first);
        original.save(second);
        BindingRegistry restored = new BindingRegistry();
        for (Binding binding : new BindingStore(directory).load()) {
            restored.add(binding, 8);
        }
        assertEquals(first, restored.byRod(first.rodId()));
        assertEquals(second, restored.byRod(second.rodId()));
    }

    @Test
    void stateReplacementAndDeletionAreIdempotent() throws IOException {
        BindingStore store = new BindingStore(directory);
        Binding binding = Fixtures.binding(UUID.randomUUID(), 0);
        store.save(binding);
        store.save(binding.withState(Binding.State.RELEASED));
        assertEquals(Binding.State.RELEASED, store.load().getFirst().state());
        store.delete(binding.id());
        store.delete(binding.id());
        assertTrue(store.load().isEmpty());
    }

    @Test
    void corruptionFailsExplicitlyAndLeavesFileIntact() throws IOException {
        BindingStore store = new BindingStore(directory);
        Binding binding = Fixtures.binding(UUID.randomUUID(), 0);
        store.save(binding);
        Path file = directory.resolve(binding.id() + ".dat");
        byte[] data = Files.readAllBytes(file);
        data[data.length - 1] ^= 1;
        Files.write(file, data);
        assertThrows(IOException.class, store::load);
        assertTrue(Files.exists(file));
    }

    @Test
    void truncatedOversizedAndMisnamedRecordsAreRejected() throws IOException {
        BindingStore store = new BindingStore(directory);
        Binding binding = Fixtures.binding(UUID.randomUUID(), 0);
        Path file = directory.resolve(binding.id() + ".dat");
        Files.write(file, new byte[] {1, 2, 3});
        assertThrows(IOException.class, store::load);
        Files.write(file, new byte[9000]);
        assertThrows(IOException.class, store::load);
        Files.delete(file);
        store.save(binding);
        Files.move(file, directory.resolve(UUID.randomUUID() + ".dat"));
        assertThrows(IOException.class, store::load);
    }

    @Test
    void incompleteTemporaryWritesAreNeverLoaded() throws IOException {
        Files.write(directory.resolve(UUID.randomUUID() + ".tmp"), new byte[] {1, 2});
        assertTrue(new BindingStore(directory).load().isEmpty());
    }
}
