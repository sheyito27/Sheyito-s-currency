package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.WaystoneTollConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers the waystone toll: always charges, can leave the balance negative, never blocks. */
class WaystoneTollLogicTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private static WaystoneTollConfig defaultConfig() {
        WaystoneTollConfig config = new WaystoneTollConfig();
        config.enabled = true;
        config.cost = 100.0;
        return config;
    }

    @Test
    void isEnabledRequiresFlagAndPositiveCost() {
        assertFalse(WaystoneTollLogic.isEnabled(null));

        WaystoneTollConfig disabled = defaultConfig();
        disabled.enabled = false;
        assertFalse(WaystoneTollLogic.isEnabled(disabled));

        WaystoneTollConfig zeroCost = defaultConfig();
        zeroCost.cost = 0;
        assertFalse(WaystoneTollLogic.isEnabled(zeroCost));

        assertTrue(WaystoneTollLogic.isEnabled(defaultConfig()));
    }

    @Test
    void applyTollDeductsExactCostWhenBalanceCoversIt() {
        EconomyManager economy = EconomyManager.createForTesting();
        UUID uuid = UUID.randomUUID();
        economy.give(uuid, 500.0);

        WaystoneTollLogic.applyToll(economy, defaultConfig(), uuid);

        assertEquals(400.0, economy.getBalance(uuid));
    }

    @Test
    void applyTollLeavesBalanceNegativeWhenInsufficient() {
        EconomyManager economy = EconomyManager.createForTesting();
        UUID uuid = UUID.randomUUID();
        economy.give(uuid, 30.0);

        WaystoneTollLogic.applyToll(economy, defaultConfig(), uuid);

        assertEquals(-70.0, economy.getBalance(uuid), "30 - 100 cost = -70, never blocked");
    }
}
