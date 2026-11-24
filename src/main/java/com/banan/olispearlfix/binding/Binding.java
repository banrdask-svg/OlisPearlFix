package com.banan.olispearlfix.binding;

import java.util.Objects;
import java.util.UUID;

public record Binding(UUID id, UUID ownerId, UUID rodId, BlockPosition plate,
                      String worldName, String plateMaterial, int signal,
                      UUID pearlId, BlockPosition pearlPosition, UUID hookId,
                      double hookX, double hookY, double hookZ, State state) {
    public enum State {
        HELD, DISARMING, RELEASED
    }

    public Binding {
        Objects.requireNonNull(id);
        Objects.requireNonNull(ownerId);
        Objects.requireNonNull(rodId);
        Objects.requireNonNull(plate);
        Objects.requireNonNull(worldName);
        Objects.requireNonNull(plateMaterial);
        Objects.requireNonNull(pearlId);
        Objects.requireNonNull(pearlPosition);
        Objects.requireNonNull(hookId);
        Objects.requireNonNull(state);
        if (signal < 1 || signal > 15 || !plate.worldId().equals(pearlPosition.worldId())
                || !Double.isFinite(hookX) || !Double.isFinite(hookY) || !Double.isFinite(hookZ)) {
            throw new IllegalArgumentException("Invalid binding coordinates or signal");
        }
    }

    public Binding withState(State newState) {
        return new Binding(id, ownerId, rodId, plate, worldName, plateMaterial, signal,
                pearlId, pearlPosition, hookId, hookX, hookY, hookZ, newState);
    }

    public boolean holdsPlate() {
        return state != State.RELEASED;
    }

    public Binding withPearlPosition(BlockPosition position) {
        return new Binding(id, ownerId, rodId, plate, worldName, plateMaterial, signal,
                pearlId, position, hookId, hookX, hookY, hookZ, state);
    }
}
