package com.sheyito.economicmaster.chunk;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers the per-player claim count (a live "held now" total, not a lifetime one) used by
 * ChunkClaimLogic's pricing. */
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

    @Test
    void decrementReversesAnIncrement() {
        ChunkClaimManager manager = ChunkClaimManager.createForTesting();
        UUID uuid = UUID.randomUUID();
        manager.incrementClaimCount(uuid);
        manager.incrementClaimCount(uuid);

        manager.decrementClaimCount(uuid);

        assertEquals(1, manager.getClaimCount(uuid));
    }

    @Test
    void decrementNeverGoesBelowZero() {
        ChunkClaimManager manager = ChunkClaimManager.createForTesting();
        UUID uuid = UUID.randomUUID();

        manager.decrementClaimCount(uuid);
        manager.decrementClaimCount(uuid);

        assertEquals(0, manager.getClaimCount(uuid), "unclaiming with nothing tracked stays at 0, never negative");
    }

    @Test
    void claimingUnclaimingDownToOneThenClaimingAgainPricesLikeTheSecondChunk() {
        ChunkClaimManager manager = ChunkClaimManager.createForTesting();
        UUID uuid = UUID.randomUUID();

        for (int i = 0; i < 34; i++) {
            manager.incrementClaimCount(uuid);
        }
        for (int i = 0; i < 33; i++) {
            manager.decrementClaimCount(uuid);
        }

        assertEquals(1, manager.getClaimCount(uuid), "34 claimed then 33 unclaimed leaves exactly 1 held - "
                + "the next claim must price as the 2nd chunk, not the 35th");
    }
}
