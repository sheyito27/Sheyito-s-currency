package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.config.ChunkClaimConfig;
import com.sheyito.economicmaster.economy.EconomyManager;

import java.util.UUID;

/**
 * Pure chunk-claim charge logic, extracted out of {@link FtbChunksIntegration} so it can be unit
 * tested without needing real FTB Chunks/FTB Library classes on the test classpath (they're
 * compileOnly, not testImplementation) - mirrors {@code WaystoneTollLogic}.
 */
final class ChunkClaimLogic {

    private ChunkClaimLogic() {
    }

    static boolean isEnabled(ChunkClaimConfig config) {
        return config != null && config.enabled && config.cost > 0;
    }

    /** Read-only check, never mutates the balance - used in the BEFORE_CLAIM phase. */
    static boolean canAfford(EconomyManager economy, ChunkClaimConfig config, UUID uuid) {
        if (!isEnabled(config)) {
            return true;
        }
        return economy.getBalance(uuid) >= config.cost;
    }

    /**
     * Deducts the claim cost via {@link EconomyManager#take(UUID, double)}, which requires
     * sufficient funds and never leaves the balance negative - only ever called from the
     * AFTER_CLAIM phase, once the claim is confirmed real (not simulated).
     *
     * @return true if the cost was deducted (or the charge is disabled/free)
     */
    static boolean chargeClaim(EconomyManager economy, ChunkClaimConfig config, UUID uuid) {
        if (!isEnabled(config)) {
            return true;
        }
        return economy.take(uuid, config.cost);
    }
}
