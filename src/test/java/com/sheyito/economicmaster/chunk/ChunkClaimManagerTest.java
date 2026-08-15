package com.sheyito.economicmaster.chunk;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers the per-player claim count used by ChunkClaimLogic's quadratic pricing. */
class ChunkClaimManagerTest {

    @Test
    void newPlayerHasZeroClaims() {
        ChunkClaimManager manager = ChunkClaimManager.createForTesting();
        UUID uuid = UUID.randomUUID();

        assertEquals(0, manager.getClaimCount(uuid));
    }

    @Test
    void incrementIncreasesTheCountByOneEachTime() {
        ChunkClaimManager manager = ChunkClaimManager.createForTesting();
        UUID uuid = UUID.randomUUID();

        manager.incrementClaimCount(uuid);
        manager.incrementClaimCount(uuid);
        manager.incrementClaimCount(uuid);

        assertEquals(3, manager.getClaimCount(uuid));
    }

    @Test
    void countsAreIndependentPerPlayer() {
        ChunkClaimManager manager = ChunkClaimManager.createForTesting();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        manager.incrementClaimCount(first);
        manager.incrementClaimCount(first);
        manager.incrementClaimCount(second);

        assertEquals(2, manager.getClaimCount(first));
        assertEquals(1, manager.getClaimCount(second));
    }
}
