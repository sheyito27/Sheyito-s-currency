package com.sheyito.economicmaster.events;

import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.DimensionUnlockConfig;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.dimension.DimensionUnlockManager;
import com.sheyito.economicmaster.economy.EconomyManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Covers dimension unlocking: overworld is always free, other dimensions cost once and stick. */
class DimensionUnlockListenerTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private interface WithManagers {
        void run(EconomyManager economy, DimensionUnlockManager unlocks, ServerPlayer player, UUID uuid) throws Exception;
    }

    private void withManagers(WithManagers test) throws Exception {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;

        EconomyManager economy = EconomyManager.createForTesting();
        DimensionUnlockManager unlocks = DimensionUnlockManager.createForTesting();

        UUID uuid = UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(uuid);

        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            test.run(economy, unlocks, player, uuid);
        }
    }

    private static DimensionUnlockConfig defaultConfig() {
        DimensionUnlockConfig config = new DimensionUnlockConfig();
        config.enabled = true;
        config.price = 5000.0;
        return config;
    }

    @Test
    void overworldNeverRequiresPayment() throws Exception {
        withManagers((economy, unlocks, player, uuid) -> {
            // no balance given at all - would fail to afford anything

            boolean allowed = DimensionUnlockListener.handleTravel(economy, unlocks, defaultConfig(), player, Level.OVERWORLD);

            assertTrue(allowed);
            assertEquals(0.0, economy.getBalance(uuid));
        });
    }

    @Test
    void alreadyUnlockedDimensionNeverChargesAgain() throws Exception {
        withManagers((economy, unlocks, player, uuid) -> {
            unlocks.unlock(uuid, Level.NETHER);
            // balance is 0 - would fail to afford the price if it tried to charge

            boolean allowed = DimensionUnlockListener.handleTravel(economy, unlocks, defaultConfig(), player, Level.NETHER);

            assertTrue(allowed);
            assertEquals(0.0, economy.getBalance(uuid), "already unlocked, should never touch the balance");
        });
    }

    @Test
    void sufficientFundsUnlocksAndCharges() throws Exception {
        withManagers((economy, unlocks, player, uuid) -> {
            economy.give(uuid, 6000.0);

            boolean allowed = DimensionUnlockListener.handleTravel(economy, unlocks, defaultConfig(), player, Level.END);

            assertTrue(allowed);
            assertEquals(1000.0, economy.getBalance(uuid), "6000 - 5000 price = 1000");
            assertTrue(unlocks.isUnlocked(uuid, Level.END));
        });
    }

    @Test
    void insufficientFundsBlocksAndDoesNotUnlock() throws Exception {
        withManagers((economy, unlocks, player, uuid) -> {
            economy.give(uuid, 100.0);

            boolean allowed = DimensionUnlockListener.handleTravel(economy, unlocks, defaultConfig(), player, Level.NETHER);

            assertFalse(allowed);
            assertEquals(100.0, economy.getBalance(uuid), "take() never mutates the balance when it returns false");
            assertFalse(unlocks.isUnlocked(uuid, Level.NETHER));
        });
    }
}
