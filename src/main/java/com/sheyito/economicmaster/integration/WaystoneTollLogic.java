package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.config.WaystoneTollConfig;
import com.sheyito.economicmaster.economy.EconomyManager;

import java.util.UUID;

/**
 * Pure toll logic, extracted out of {@link WaystonesIntegration} so it can be unit tested
 * without needing real Waystones/Balm classes on the test classpath (they're compileOnly, not
 * testImplementation) - mirrors the plain-value-in/out static-method style used by
 * {@code PlayerDeathPenaltyListener#applyDeathPenalty}.
 */
final class WaystoneTollLogic {

    private WaystoneTollLogic() {
    }

    static boolean isEnabled(WaystoneTollConfig config) {
        return config != null && config.enabled && config.cost > 0;
    }

    /**
     * Read-only check, never mutates the balance - used to decide whether to block the
     * teleport before it happens. When the toll is disabled, everything is "affordable".
     */
    static boolean canAfford(EconomyManager economy, WaystoneTollConfig config, UUID uuid) {
        if (!isEnabled(config)) {
            return true;
        }
        return economy.getBalance(uuid) >= config.cost;
    }

    /**
     * Deducts the toll via {@link EconomyManager#take(UUID, double)}, which requires sufficient
     * funds and never leaves the balance negative - only ever called after {@link #canAfford}
     * confirmed the player can pay.
     *
     * @return true if the toll was deducted (or the toll is disabled/free)
     */
    static boolean chargeToll(EconomyManager economy, WaystoneTollConfig config, UUID uuid) {
        if (!isEnabled(config)) {
            return true;
        }
        return economy.take(uuid, config.cost);
    }
}
