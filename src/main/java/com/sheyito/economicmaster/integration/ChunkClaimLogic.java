package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.config.ChunkClaimConfig;
import com.sheyito.economicmaster.economy.EconomyManager;

import java.util.UUID;

/**
 * Pure chunk-claim charge logic, extracted out of {@link FtbChunksIntegration} so it can be unit
 * tested without needing real FTB Chunks/FTB Library classes on the test classpath (they're
 * compileOnly, not testImplementation) - mirrors {@code WaystoneTollLogic}.
 *
 * <p>Unlike every other charge in this mod, the price is not configurable: claiming your
 * {@code n}-th chunk (1-based, {@code n = alreadyClaimed + 1}) costs {@code BASE_COST * n^2}
 * Sheyicoins - a deliberate anti-hoarding curve (1st chunk 1000, 2nd 4000, 3rd 9000, ...) that
 * server owners can't flatten via config.
 */
final class ChunkClaimLogic {

    private static final double BASE_COST = 1000.0;

    private ChunkClaimLogic() {
    }

    static boolean isEnabled(ChunkClaimConfig config) {
        return config != null && config.enabled;
    }

    /** Cost of claiming the next chunk, given how many the player has already claimed. */
    static double costFor(int alreadyClaimed) {
        long n = alreadyClaimed + 1L;
        return BASE_COST * n * n;
    }

    /** Read-only check, never mutates the balance - used in the BEFORE_CLAIM phase. */
    static boolean canAfford(EconomyManager economy, ChunkClaimConfig config, UUID uuid, int alreadyClaimed) {
        if (!isEnabled(config)) {
            return true;
        }
        return economy.getBalance(uuid) >= costFor(alreadyClaimed);
    }

    /**
     * Deducts the claim cost via {@link EconomyManager#take(UUID, double)}, which requires
     * sufficient funds and never leaves the balance negative - only ever called from the
     * AFTER_CLAIM phase, once the claim is confirmed real (not simulated).
     *
     * @return true if the cost was deducted (or the charge is disabled)
     */
    static boolean chargeClaim(EconomyManager economy, ChunkClaimConfig config, UUID uuid, int alreadyClaimed) {
        if (!isEnabled(config)) {
            return true;
        }
        return economy.take(uuid, costFor(alreadyClaimed));
    }
}
