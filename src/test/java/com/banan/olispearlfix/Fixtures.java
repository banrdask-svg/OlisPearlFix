package com.banan.olispearlfix;

import com.banan.olispearlfix.binding.Binding;
import com.banan.olispearlfix.binding.BlockPosition;
import java.util.UUID;

public final class Fixtures {
    private Fixtures() {
    }

    public static Binding binding(UUID owner, int x) {
        UUID world = UUID.nameUUIDFromBytes(new byte[] {1});
        return new Binding(UUID.randomUUID(), owner, UUID.randomUUID(), new BlockPosition(world, x, 64, 0),
                "world", "OAK_PRESSURE_PLATE", 15, UUID.randomUUID(), new BlockPosition(world, x, 62, 0),
                UUID.randomUUID(), x + 0.5, 64.1, 0.5, Binding.State.HELD);
    }
}
