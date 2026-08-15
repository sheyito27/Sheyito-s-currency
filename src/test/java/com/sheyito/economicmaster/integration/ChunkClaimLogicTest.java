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

/** Covers the chunk claim charge: sqrt(n)-scaled cost per player, blocks (never charges) when short. */
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
        return config;
    }

    @Test
    void isEnabledRequiresFlag() {
        assertFalse(ChunkClaimLogic.isEnabled(null));

        ChunkClaimConfig disabled = defaultConfig();
        disabled.enabled = false;
        assertFalse(ChunkClaimLogic.isEnabled(disabled));

        assertTrue(ChunkClaimLogic.isEnabled(defaultConfig()));
    }

    @Test
    void costForScalesWithSquareRootAndBase1000() {
        assertEquals(1000.0, ChunkClaimLogic.costFor(0), "1st chunk: 1000 * sqrt(1)");
        assertEquals(2000.0, ChunkClaimLogic.costFor(3), "4th chunk: 1000 * sqrt(4)");
        assertEquals(3000.0, ChunkClaimLogic.costFor(8), "9th chunk: 1000 * sqrt(9)");
        assertEquals(10000.0, ChunkClaimLogic.costFor(99), "100th chunk: 1000 * sqrt(100)");
    }

    @Test
    void canAffordIsTrueWhenBalanceCoversTheNextChunk() {
        withEconomy((economy, uuid) -> {
            economy.give(uuid, 2000.0);

            assertTrue(ChunkClaimLogic.canAfford(economy, defaultConfig(), uuid, 3), "4th chunk costs exactly 2000");
        });
    }

    @Test
    void canAffordIsFalseWhenBalanceIsInsufficientForTheNextChunk() {
        withEconomy((economy, uuid) -> {
            economy.give(uuid, 1999.99);

            assertFalse(ChunkClaimLogic.canAfford(economy, defaultConfig(), uuid, 3));
        });
    }

    @Test
    void canAffordIsAlwaysTrueWhenDisabled() {
        withEconomy((economy, uuid) -> {
            ChunkClaimConfig disabled = defaultConfig();
            disabled.enabled = false;

            assertTrue(ChunkClaimLogic.canAfford(economy, disabled, uuid, 50), "no balance given, but the charge is off");
        });
    }

    @Test
    void chargeClaimDeductsTheCostForTheGivenCountAndReturnsTrueWhenAffordable() {
        withEconomy((economy, uuid) -> {
            economy.give(uuid, 5000.0);

            assertTrue(ChunkClaimLogic.chargeClaim(economy, defaultConfig(), uuid, 0));

            assertEquals(4000.0, economy.getBalance(uuid), "5000 - 1000 (1st chunk) = 4000");
        });
    }

    @Test
    void chargeClaimLeavesBalanceUntouchedAndReturnsFalseWhenInsufficient() {
        withEconomy((economy, uuid) -> {
            economy.give(uuid, 2999.0);

            assertFalse(ChunkClaimLogic.chargeClaim(economy, defaultConfig(), uuid, 8), "9th chunk costs 3000");

            assertEquals(2999.0, economy.getBalance(uuid), "take() never mutates the balance when it returns false");
        });
    }
}
