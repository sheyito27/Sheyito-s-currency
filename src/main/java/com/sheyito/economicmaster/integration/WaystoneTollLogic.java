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
     * Deducts the toll via {@link EconomyManager#charge(UUID, double)}, which never checks
     * funds first - the balance can go negative. There is no separate "debt" tracking for that,
     * a negative balance is just a negative balance, visible via /bal.
     */
    static void applyToll(EconomyManager economy, WaystoneTollConfig config, UUID uuid) {
        economy.charge(uuid, config.cost);
    }
}
