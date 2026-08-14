package com.sheyito.economicmaster.events;

import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.DebtConfig;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.debt.DebtManager;
import com.sheyito.economicmaster.economy.EconomyManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Covers the two branches of the death penalty: flat-below-threshold (can create debt) vs percent-above-threshold (never does). */
class PlayerDeathDebtListenerTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private interface WithPenalty {
        void run(EconomyManager economy, DebtManager debtManager, MinecraftServer server, ServerPlayer player, UUID uuid) throws Exception;
    }

    private void withPenalty(DebtConfig config, long currentGameDay, WithPenalty test) throws Exception {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;

        EconomyManager economy = EconomyManager.createForTesting();
        DebtManager debtManager = DebtManager.createForTesting();

        UUID uuid = UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(uuid);

        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel overworld = mock(ServerLevel.class);
        when(server.overworld()).thenReturn(overworld);
        when(overworld.getGameTime()).thenReturn(currentGameDay * 24000L);

        EconomyManager.installForTesting(economy);
        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            test.run(economy, debtManager, server, player, uuid);
        } finally {
            EconomyManager.installForTesting(null);
        }
    }

    private static DebtConfig defaultConfig() {
        DebtConfig config = new DebtConfig();
        config.enabled = true;
        config.balanceThreshold = 500.0;
        config.penaltyAmount = 500.0;
        config.penaltyPercent = 0.30;
        config.deadlineGameDays = 1;
        config.broadcastOnDebtIncurred = true;
        return config;
    }

    @Test
    void belowThresholdChargesFlatAmountAndCreatesDebtWhenNegative() throws Exception {
        withPenalty(defaultConfig(), 5, (economy, debtManager, server, player, uuid) -> {
            economy.give(uuid, 300.0); // <= threshold of 500

            PlayerDeathDebtListener.applyDeathPenalty(economy, debtManager, defaultConfig(), server, player);

            assertEquals(-200.0, economy.getBalance(uuid), "300 - flat 500 penalty = -200");
            assertEquals(Optional.of(6L), debtManager.getDueGameDay(uuid), "deadline is deathDay + deadlineGameDays");
        });
    }

    @Test
    void belowThresholdStayingNonNegativeCreatesNoDebt() throws Exception {
        DebtConfig config = defaultConfig();
        config.penaltyAmount = 100.0;
        withPenalty(config, 5, (economy, debtManager, server, player, uuid) -> {
            economy.give(uuid, 500.0); // exactly at threshold

            PlayerDeathDebtListener.applyDeathPenalty(economy, debtManager, config, server, player);

            assertEquals(400.0, economy.getBalance(uuid));
            assertEquals(Optional.empty(), debtManager.getDueGameDay(uuid));
        });
    }

    @Test
    void aboveThresholdTakesExactPercentAndNeverGoesNegative() throws Exception {
        withPenalty(defaultConfig(), 5, (economy, debtManager, server, player, uuid) -> {
            economy.give(uuid, 2000.0); // > threshold of 500

            PlayerDeathDebtListener.applyDeathPenalty(economy, debtManager, defaultConfig(), server, player);

            assertEquals(1400.0, economy.getBalance(uuid), "2000 - 30% of 2000 (600) = 1400");
            assertEquals(Optional.empty(), debtManager.getDueGameDay(uuid), "the percent branch must never create debt");
        });
    }

    @Test
    void isOverdueOnceDeadlineArrivesAfterDeath() throws Exception {
        withPenalty(defaultConfig(), 5, (economy, debtManager, server, player, uuid) -> {
            economy.give(uuid, 300.0);
            PlayerDeathDebtListener.applyDeathPenalty(economy, debtManager, defaultConfig(), server, player);

            when(server.overworld().getGameTime()).thenReturn(6L * 24000L);
            assertTrue(debtManager.isOverdue(server, uuid));
        });
    }
}
