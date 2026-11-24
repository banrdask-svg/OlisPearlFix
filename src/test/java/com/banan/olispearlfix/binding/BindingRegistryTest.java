package com.banan.olispearlfix.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.banan.olispearlfix.Fixtures;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BindingRegistryTest {
    @Test
    void multiplePlayersAndRodsRemainIndependent() {
        BindingRegistry registry = new BindingRegistry();
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        Binding first = Fixtures.binding(alice, 0);
        Binding second = Fixtures.binding(alice, 16);
        Binding third = Fixtures.binding(bob, 32);
        registry.add(first, 2);
        registry.add(second, 2);
        registry.add(third, 2);
        assertEquals(first, registry.byRod(first.rodId()));
        assertEquals(second, registry.byPearl(second.pearlId()));
        assertEquals(third, registry.byPlate(third.plate()));
        assertEquals(2, registry.forOwner(alice).size());
        assertEquals(1, registry.forOwner(bob).size());
        assertEquals(1, registry.inChunk(first.plate().chunk()).size());
    }

    @Test
    void duplicatedItemIdentityCannotCreateAnotherChamber() {
        BindingRegistry registry = new BindingRegistry();
        Binding first = Fixtures.binding(UUID.randomUUID(), 0);
        registry.add(first, 8);
        Binding other = Fixtures.binding(UUID.randomUUID(), 32);
        Binding clone = new Binding(other.id(), other.ownerId(), first.rodId(), other.plate(), other.worldName(),
                other.plateMaterial(), other.signal(), other.pearlId(), other.pearlPosition(), other.hookId(),
                other.hookX(), other.hookY(), other.hookZ(), other.state());
        assertThrows(IllegalArgumentException.class, () -> registry.add(clone, 8));
        assertEquals(1, registry.all().size());
    }

    @Test
    void rejectsDuplicatePlatePearlAndBinding() {
        BindingRegistry registry = new BindingRegistry();
        Binding first = Fixtures.binding(UUID.randomUUID(), 0);
        registry.add(first, 8);
        assertThrows(IllegalArgumentException.class, () -> registry.add(first, 8));
        Binding second = Fixtures.binding(UUID.randomUUID(), 0);
        assertThrows(IllegalArgumentException.class, () -> registry.add(second, 8));
        Binding sharedPearl = new Binding(second.id(), second.ownerId(), second.rodId(),
                new BlockPosition(first.plate().worldId(), 32, 64, 0), second.worldName(), second.plateMaterial(),
                15, first.pearlId(), first.pearlPosition(), second.hookId(), 32.5, 64.1, 0.5, Binding.State.HELD);
        assertThrows(IllegalArgumentException.class, () -> registry.add(sharedPearl, 8));
    }

    @Test
    void limitAndRemovalCleanEveryIndex() {
        BindingRegistry registry = new BindingRegistry();
        UUID owner = UUID.randomUUID();
        Binding first = Fixtures.binding(owner, -1);
        registry.add(first, 1);
        assertThrows(IllegalArgumentException.class, () -> registry.add(Fixtures.binding(owner, 16), 1));
        registry.remove(first.id());
        assertNull(registry.byRod(first.rodId()));
        assertNull(registry.byPearl(first.pearlId()));
        assertNull(registry.byPlate(first.plate()));
        assertTrue(registry.forOwner(owner).isEmpty());
        assertTrue(registry.inChunk(first.plate().chunk()).isEmpty());
        registry.add(Fixtures.binding(owner, 16), 1);
    }

    @Test
    void disarmingStillHoldsPlateWhileReleasedStateDoesNot() {
        Binding binding = Fixtures.binding(UUID.randomUUID(), 0);
        assertTrue(binding.holdsPlate());
        assertTrue(binding.withState(Binding.State.DISARMING).holdsPlate());
        assertFalse(binding.withState(Binding.State.RELEASED).holdsPlate());
    }

    @Test
    void negativeCoordinatesUseFloorChunkDivision() {
        BlockPosition location = new BlockPosition(UUID.randomUUID(), -1, 64, -17);
        assertEquals(-1, location.chunk().x());
        assertEquals(-2, location.chunk().z());
    }
}
