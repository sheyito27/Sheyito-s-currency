package com.sheyito.economicmaster.debt;

import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Backs the "1 in-game day to repay or it's overdue" deadline exposed by /debt. */
class DebtManagerTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private interface WithDebt {
        void run(DebtManager debtManager, EconomyManager economy, MinecraftServer server, UUID player) throws Exception;
    }

    private void withDebt(long currentGameDay, WithDebt test) throws Exception {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;

        EconomyManager economy = EconomyManager.createForTesting();
        EconomyManager.installForTesting(economy);

        UUID uuid = UUID.randomUUID();

        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel overworld = mock(ServerLevel.class);
        when(server.overworld()).thenReturn(overworld);
        when(overworld.getGameTime()).thenReturn(currentGameDay * 24000L);

        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            test.run(DebtManager.createForTesting(), economy, server, uuid);
        } finally {
            EconomyManager.installForTesting(null);
        }
    }

    @Test
    void playerWithNoDebtHasNoDueDay() throws Exception {
        withDebt(5, (debtManager, economy, server, player) -> {
            assertEquals(Optional.empty(), debtManager.getDueGameDay(player));
            assertEquals(0.0, debtManager.getDebtAmount(player));
            assertFalse(debtManager.isOverdue(server, player));
        });
    }

    @Test
    void incurDebtRecordsDeadlineAndAmountTracksBalance() throws Exception {
        withDebt(5, (debtManager, economy, server, player) -> {
            economy.charge(player, 300.0);
            debtManager.incurDebt(player, 6L);

            assertEquals(Optional.of(6L), debtManager.getDueGameDay(player));
            assertEquals(300.0, debtManager.getDebtAmount(player));
        });
    }

    @Test
    void notOverdueBeforeDeadline() throws Exception {
        withDebt(5, (debtManager, economy, server, player) -> {
            economy.charge(player, 300.0);
            debtManager.incurDebt(player, 6L);

            assertFalse(debtManager.isOverdue(server, player));
        });
    }

    @Test
    void overdueOnceDeadlineDayArrivesWhileStillNegative() throws Exception {
        withDebt(5, (debtManager, economy, server, player) -> {
            economy.charge(player, 300.0);
            debtManager.incurDebt(player, 6L);

            when(server.overworld().getGameTime()).thenReturn(6L * 24000L);

            assertTrue(debtManager.isOverdue(server, player));
        });
    }

    @Test
    void debtClearsItselfOnceBalanceIsRepaid() throws Exception {
        withDebt(5, (debtManager, economy, server, player) -> {
            economy.charge(player, 300.0);
            debtManager.incurDebt(player, 6L);

            economy.give(player, 300.0);

            assertEquals(Optional.empty(), debtManager.getDueGameDay(player), "repaying in full must clear the deadline");
            assertEquals(0.0, debtManager.getDebtAmount(player));
        });
    }
}
