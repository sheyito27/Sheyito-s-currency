package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.config.WaystoneTollConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/** Covers the waystone toll: blocks (never charges) when the balance can't cover the cost. */
class WaystoneTollLogicTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private interface WithEconomy {
        void run(EconomyManager economy, UUID uuid);
    }

    private void withEconomy(WithEconomy test) {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;

        EconomyManager economy = EconomyManager.createForTesting();
        UUID uuid = UUID.randomUUID();

        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            test.run(economy, uuid);
        }
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
    void canAffordIsTrueWhenBalanceCoversCost() {
        withEconomy((economy, uuid) -> {
            economy.give(uuid, 100.0);

            assertTrue(WaystoneTollLogic.canAfford(economy, defaultConfig(), uuid));
        });
    }

    @Test
    void canAffordIsFalseWhenBalanceIsInsufficient() {
        withEconomy((economy, uuid) -> {
            economy.give(uuid, 99.99);

            assertFalse(WaystoneTollLogic.canAfford(economy, defaultConfig(), uuid));
        });
    }

    @Test
    void canAffordIsAlwaysTrueWhenDisabled() {
        withEconomy((economy, uuid) -> {
            WaystoneTollConfig disabled = defaultConfig();
            disabled.enabled = false;

            assertTrue(WaystoneTollLogic.canAfford(economy, disabled, uuid), "no balance given, but toll is off");
        });
    }

    @Test
    void chargeTollDeductsExactCostAndReturnsTrueWhenAffordable() {
        withEconomy((economy, uuid) -> {
            economy.give(uuid, 500.0);

            assertTrue(WaystoneTollLogic.chargeToll(economy, defaultConfig(), uuid));

            assertEquals(400.0, economy.getBalance(uuid));
        });
    }

    @Test
    void chargeTollLeavesBalanceUntouchedAndReturnsFalseWhenInsufficient() {
        withEconomy((economy, uuid) -> {
            economy.give(uuid, 30.0);

            assertFalse(WaystoneTollLogic.chargeToll(economy, defaultConfig(), uuid));

            assertEquals(30.0, economy.getBalance(uuid), "take() never mutates the balance when it returns false");
        });
    }
}
