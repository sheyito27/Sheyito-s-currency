package com.sheyito.economicmaster.chunk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers the per-player claim count (a live "held now" total, not a lifetime one) used by
 * ChunkClaimLogic's pricing, and the force-loaded-chunk count + rent-billing bookkeeping added
 * alongside it when ChunkClaimManager was renamed/extended into ChunkClaimRegistry. */
class ChunkClaimRegistryTest {

    @Test
    void newPlayerHasZeroClaims() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID uuid = UUID.randomUUID();

        assertEquals(0, registry.getClaimCount(uuid));
    }

    @Test
    void incrementIncreasesTheCountByOneEachTime() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID uuid = UUID.randomUUID();

        registry.incrementClaimCount(uuid);
        registry.incrementClaimCount(uuid);
        registry.incrementClaimCount(uuid);

        assertEquals(3, registry.getClaimCount(uuid));
    }

    @Test
    void countsAreIndependentPerPlayer() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        registry.incrementClaimCount(first);
        registry.incrementClaimCount(first);
        registry.incrementClaimCount(second);

        assertEquals(2, registry.getClaimCount(first));
        assertEquals(1, registry.getClaimCount(second));
    }

    @Test
    void decrementReversesAnIncrement() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID uuid = UUID.randomUUID();
        registry.incrementClaimCount(uuid);
        registry.incrementClaimCount(uuid);

        registry.decrementClaimCount(uuid);

        assertEquals(1, registry.getClaimCount(uuid));
    }

    @Test
    void decrementNeverGoesBelowZero() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID uuid = UUID.randomUUID();

        registry.decrementClaimCount(uuid);
        registry.decrementClaimCount(uuid);

        assertEquals(0, registry.getClaimCount(uuid), "unclaiming with nothing tracked stays at 0, never negative");
    }

    @Test
    void resetDropsTheCountToZero() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID uuid = UUID.randomUUID();
        registry.incrementClaimCount(uuid);
        registry.incrementClaimCount(uuid);
        registry.incrementClaimCount(uuid);

        registry.resetClaimCount(uuid);

        assertEquals(0, registry.getClaimCount(uuid));
    }

    @Test
    void resetDoesNotAffectOtherPlayers() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        registry.incrementClaimCount(first);
        registry.incrementClaimCount(second);
        registry.incrementClaimCount(second);

        registry.resetClaimCount(first);

        assertEquals(0, registry.getClaimCount(first));
        assertEquals(2, registry.getClaimCount(second));
    }

    @Test
    void claimingUnclaimingDownToOneThenClaimingAgainPricesLikeTheSecondChunk() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID uuid = UUID.randomUUID();

        for (int i = 0; i < 34; i++) {
            registry.incrementClaimCount(uuid);
        }
        for (int i = 0; i < 33; i++) {
            registry.decrementClaimCount(uuid);
        }

        assertEquals(1, registry.getClaimCount(uuid), "34 claimed then 33 unclaimed leaves exactly 1 held - "
                + "the next claim must price as the 2nd chunk, not the 35th");
    }

    // === Chunks force-loaded ===

    @Test
    void newPlayerHasZeroLoadedChunks() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        assertEquals(0, registry.getLoadedCount(UUID.randomUUID()));
    }

    @Test
    void incrementLoadedCountIncreasesByOneAndSeedsTheRentDayOnlyOnce() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID uuid = UUID.randomUUID();

        registry.incrementLoadedCount(uuid, 10L);
        registry.incrementLoadedCount(uuid, 20L);

        assertEquals(2, registry.getLoadedCount(uuid));
        assertTrue(registry.playersDueForForceLoadRent(16L, 7).isEmpty(), "day 10 + 7 = day 17, not due yet at day 16");
        assertEquals(List.of(uuid), registry.playersDueForForceLoadRent(17L, 7), "seeded from the FIRST load (day 10), not the second (day 20)");
    }

    @Test
    void decrementLoadedCountFlorsAtZero() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID uuid = UUID.randomUUID();
        registry.incrementLoadedCount(uuid, 0L);

        registry.decrementLoadedCount(uuid);
        registry.decrementLoadedCount(uuid);

        assertEquals(0, registry.getLoadedCount(uuid));
    }

    @Test
    void playersDueForForceLoadRentSkipsThoseWithNothingLoaded() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID uuid = UUID.randomUUID();
        registry.incrementLoadedCount(uuid, 0L);
        registry.decrementLoadedCount(uuid);

        assertTrue(registry.playersDueForForceLoadRent(100L, 7).isEmpty());
    }

    @Test
    void playersDueForForceLoadRentSkipsThoseAlreadyPendingUnload() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID uuid = UUID.randomUUID();
        registry.incrementLoadedCount(uuid, 0L);
        registry.markPendingForceUnload(uuid);

        assertTrue(registry.playersDueForForceLoadRent(7L, 7).isEmpty(), "already waiting on a deferred unload - don't re-bill until it resolves");
    }

    @Test
    void markForceLoadRentCheckedPostponesTheNextDueDate() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID uuid = UUID.randomUUID();
        registry.incrementLoadedCount(uuid, 0L);

        registry.markForceLoadRentChecked(uuid, 7L);

        assertTrue(registry.playersDueForForceLoadRent(10L, 7).isEmpty());
        assertEquals(List.of(uuid), registry.playersDueForForceLoadRent(14L, 7));
    }

    @Test
    void clearLoadedChunksResetsCountAndPendingFlag() {
        ChunkClaimRegistry registry = ChunkClaimRegistry.createForTesting();
        UUID uuid = UUID.randomUUID();
        registry.incrementLoadedCount(uuid, 0L);
        registry.incrementLoadedCount(uuid, 0L);
        registry.markPendingForceUnload(uuid);

        registry.clearLoadedChunks(uuid);

        assertEquals(0, registry.getLoadedCount(uuid));
        assertFalse(registry.hasPendingForceUnload(uuid));
    }

    @Test
    void forceLoadRentForMatchesTheSameShapeAsChunkClaimPricingWithBaseTen() {
        assertEquals(10.0, ChunkClaimRegistry.forceLoadRentFor(10.0, 1));
        assertEquals(10.0 * Math.pow(2, 1.5), ChunkClaimRegistry.forceLoadRentFor(10.0, 2), 0.0001);
        assertEquals(0.0, ChunkClaimRegistry.forceLoadRentFor(10.0, 0), "no chunks loaded - no rent");
    }
}
