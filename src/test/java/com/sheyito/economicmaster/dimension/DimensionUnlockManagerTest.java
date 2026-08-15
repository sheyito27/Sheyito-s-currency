package com.sheyito.economicmaster.dimension;

import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the unlock/isUnlocked/lock round-trip used by /dimension lock. */
class DimensionUnlockManagerTest {

    @Test
    void unlockedDimensionIsUnlocked() {
        DimensionUnlockManager manager = DimensionUnlockManager.createForTesting();
        UUID uuid = UUID.randomUUID();

        manager.unlock(uuid, Level.NETHER);

        assertTrue(manager.isUnlocked(uuid, Level.NETHER));
    }

    @Test
    void lockingAnUnlockedDimensionRevertsIt() {
        DimensionUnlockManager manager = DimensionUnlockManager.createForTesting();
        UUID uuid = UUID.randomUUID();
        manager.unlock(uuid, Level.NETHER);

        manager.lock(uuid, Level.NETHER);

        assertFalse(manager.isUnlocked(uuid, Level.NETHER));
    }

    @Test
    void lockingOtherDimensionsIsUnaffected() {
        DimensionUnlockManager manager = DimensionUnlockManager.createForTesting();
        UUID uuid = UUID.randomUUID();
        manager.unlock(uuid, Level.NETHER);
        manager.unlock(uuid, Level.END);

        manager.lock(uuid, Level.NETHER);

        assertFalse(manager.isUnlocked(uuid, Level.NETHER));
        assertTrue(manager.isUnlocked(uuid, Level.END), "locking one dimension shouldn't touch another");
    }

    @Test
    void lockingSomethingNeverUnlockedIsANoOp() {
        DimensionUnlockManager manager = DimensionUnlockManager.createForTesting();
        UUID uuid = UUID.randomUUID();

        manager.lock(uuid, Level.NETHER);

        assertFalse(manager.isUnlocked(uuid, Level.NETHER));
    }
}
