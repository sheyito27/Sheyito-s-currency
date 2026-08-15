package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.ChunkClaimConfig;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/** Covers the chunk claim charge: blocks (never charges) when the balance can't cover the cost. */
class ChunkClaimLogicTest {

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

    private static ChunkClaimConfig defaultConfig() {
        ChunkClaimConfig config = new ChunkClaimConfig();
        config.enabled = true;
        config.cost = 200.0;
        return config;
    }

    @Test
    void isEnabledRequiresFlagAndPositiveCost() {
        assertFalse(ChunkClaimLogic.isEnabled(null));

        ChunkClaimConfig disabled = defaultConfig();
        disabled.enabled = false;
        assertFalse(ChunkClaimLogic.isEnabled(disabled));

        ChunkClaimConfig zeroCost = defaultConfig();
        zeroCost.cost = 0;
        assertFalse(ChunkClaimLogic.isEnabled(zeroCost));

        assertTrue(ChunkClaimLogic.isEnabled(defaultConfig()));
    }

    @Test
    void canAffordIsTrueWhenBalanceCoversCost() {
        withEconomy((economy, uuid) -> {
            economy.give(uuid, 200.0);

            assertTrue(ChunkClaimLogic.canAfford(economy, defaultConfig(), uuid));
        });
    }

    @Test
    void canAffordIsFalseWhenBalanceIsInsufficient() {
        withEconomy((economy, uuid) -> {
            economy.give(uuid, 199.99);

            assertFalse(ChunkClaimLogic.canAfford(economy, defaultConfig(), uuid));
        });
    }

    @Test
    void canAffordIsAlwaysTrueWhenDisabled() {
        withEconomy((economy, uuid) -> {
            ChunkClaimConfig disabled = defaultConfig();
            disabled.enabled = false;

            assertTrue(ChunkClaimLogic.canAfford(economy, disabled, uuid), "no balance given, but the charge is off");
        });
    }

    @Test
    void chargeClaimDeductsExactCostAndReturnsTrueWhenAffordable() {
        withEconomy((economy, uuid) -> {
            economy.give(uuid, 500.0);

            assertTrue(ChunkClaimLogic.chargeClaim(economy, defaultConfig(), uuid));

            assertEquals(300.0, economy.getBalance(uuid));
        });
    }

    @Test
    void chargeClaimLeavesBalanceUntouchedAndReturnsFalseWhenInsufficient() {
        withEconomy((economy, uuid) -> {
            economy.give(uuid, 50.0);

            assertFalse(ChunkClaimLogic.chargeClaim(economy, defaultConfig(), uuid));

            assertEquals(50.0, economy.getBalance(uuid), "take() never mutates the balance when it returns false");
        });
    }
}
