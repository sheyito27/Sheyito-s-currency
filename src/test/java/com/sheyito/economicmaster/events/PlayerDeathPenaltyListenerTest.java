package com.sheyito.economicmaster.events;

import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.DebtConfig;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.debt.DebtManager;
import com.sheyito.economicmaster.economy.EconomyManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Covers the death penalty: always takes {@link DebtConfig#penaltyPercent} of the balance, never going negative. */
class PlayerDeathPenaltyListenerTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private interface WithPenalty {
        void run(EconomyManager economy, MinecraftServer server, ServerPlayer player, UUID uuid) throws Exception;
    }

    private void withPenalty(WithPenalty test) throws Exception {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;

        EconomyManager economy = EconomyManager.createForTesting();

        UUID uuid = UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(uuid);

        MinecraftServer server = mock(MinecraftServer.class);

        EconomyManager.installForTesting(economy);
        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            test.run(economy, server, player, uuid);
        } finally {
            EconomyManager.installForTesting(null);
        }
    }

    private static DebtConfig defaultConfig() {
        DebtConfig config = new DebtConfig();
        config.enabled = true;
        config.penaltyPercent = 0.50;
        return config;
    }

    @Test
    void losesExactlyHalfOfBalance() throws Exception {
        withPenalty((economy, server, player, uuid) -> {
            economy.give(uuid, 2000.0);

            PlayerDeathPenaltyListener.applyDeathPenalty(economy, defaultConfig(), server, player);

            assertEquals(1000.0, economy.getBalance(uuid), "2000 - 50% of 2000 (1000) = 1000");
        });
    }

    @Test
    void smallBalanceNeverGoesBelowZero() throws Exception {
        withPenalty((economy, server, player, uuid) -> {
            economy.give(uuid, 1.0);

            PlayerDeathPenaltyListener.applyDeathPenalty(economy, defaultConfig(), server, player);

            assertEquals(0.5, economy.getBalance(uuid), "1 - 50% of 1 (0.5) = 0.5");
        });
    }

    @Test
    void zeroBalanceStaysAtZero() throws Exception {
        withPenalty((economy, server, player, uuid) -> {
            economy.give(uuid, 0.0);

            PlayerDeathPenaltyListener.applyDeathPenalty(economy, defaultConfig(), server, player);

            assertEquals(0.0, economy.getBalance(uuid));
        });
    }

    @Test
    void neverRegistersDebt() throws Exception {
        withPenalty((economy, server, player, uuid) -> {
            economy.give(uuid, 2000.0);
            DebtManager debtManager = DebtManager.createForTesting();

            PlayerDeathPenaltyListener.applyDeathPenalty(economy, defaultConfig(), server, player);

            assertEquals(Optional.empty(), debtManager.getDueGameDay(uuid), "death no longer touches DebtManager");
        });
    }
}
