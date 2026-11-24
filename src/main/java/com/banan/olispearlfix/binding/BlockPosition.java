package com.banan.olispearlfix.binding;

import java.util.UUID;

public record BlockPosition(UUID worldId, int x, int y, int z) {
    public ChunkPosition chunk() {
        return new ChunkPosition(worldId, x >> 4, z >> 4);
    }

    public BlockPosition below() {
        return new BlockPosition(worldId, x, y - 1, z);
    }
}
