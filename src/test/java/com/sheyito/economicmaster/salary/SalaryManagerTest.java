package com.sheyito.economicmaster.salary;

import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.config.SalaryConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Backs the automatic daily salary payout (no direct player command, but this is what /bal level's "salario diario" reflects). */
class SalaryManagerTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private interface WithSalary {
        void run(SalaryManager salaryManager, EconomyManager economy, MinecraftServer server, UUID player, ServerPlayer serverPlayer) throws Exception;
    }

    private void withSalary(SalaryConfig config, long currentGameDay, WithSalary test) throws Exception {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;

        EconomyManager economy = EconomyManager.createForTesting();
        EconomyManager.installForTesting(economy);

        UUID uuid = UUID.randomUUID();
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(uuid);

        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        ServerLevel overworld = mock(ServerLevel.class);
        when(server.getPlayerList()).thenReturn(playerList);
        when(server.overworld()).thenReturn(overworld);
        when(overworld.getGameTime()).thenReturn(currentGameDay * 24000L);
        when(playerList.getPlayers()).thenReturn(List.of(player));

        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            mocked.when(ConfigManager::salary).thenReturn(config);
            test.run(SalaryManager.createForTesting(), economy, server, uuid, player);
        } finally {
            EconomyManager.installForTesting(null);
        }
    }

    private static SalaryConfig defaultConfig() {
        SalaryConfig config = new SalaryConfig();
        config.enabled = true;
        config.intervalGameDays = 1;
        config.baseSalary = 10.0;
        config.maxSalary = 500.0;
        config.maxLevel = 20;
        config.xpPerCoin = 0.1;
        config.levelCurveBaseXp = 20.0;
        return config;
    }

    @Test
    void firstTickJustRecordsTheDayWithoutPayingYet() throws Exception {
        withSalary(defaultConfig(), 5, (salaryManager, economy, server, player, serverPlayer) -> {
            salaryManager.tick(server);
            assertEquals(0.0, economy.getBalance(player), "joining mid-cycle must not grant an instant payout");
        });
    }

    @Test
    void paysBaseSalaryOnceIntervalElapses() throws Exception {
        withSalary(defaultConfig(), 5, (salaryManager, economy, server, player, serverPlayer) -> {
            salaryManager.tick(server); // records day 5 as the baseline, no payout
            when(server.overworld().getGameTime()).thenReturn(6L * 24000L);

            salaryManager.tick(server);

            assertEquals(10.0, economy.getBalance(player), "level 0 -> baseSalary");
        });
    }

    @Test
    void doesNotPayAgainBeforeTheIntervalElapses() throws Exception {
        withSalary(defaultConfig(), 5, (salaryManager, economy, server, player, serverPlayer) -> {
            salaryManager.tick(server);
            when(server.overworld().getGameTime()).thenReturn(6L * 24000L);
            salaryManager.tick(server);
            salaryManager.tick(server); // same day, should not double-pay

            assertEquals(10.0, economy.getBalance(player));
        });
    }

    @Test
    void disabledSalaryNeverPays() throws Exception {
        SalaryConfig config = defaultConfig();
        config.enabled = false;
        withSalary(config, 5, (salaryManager, economy, server, player, serverPlayer) -> {
            salaryManager.tick(server);
            when(server.overworld().getGameTime()).thenReturn(20L * 24000L);
            salaryManager.tick(server);

            assertEquals(0.0, economy.getBalance(player));
        });
    }

    @Test
    void salaryPayoutCountsAsEarnedXp() throws Exception {
        withSalary(defaultConfig(), 5, (salaryManager, economy, server, player, serverPlayer) -> {
            salaryManager.tick(server);
            when(server.overworld().getGameTime()).thenReturn(6L * 24000L);
            salaryManager.tick(server);

            assertEquals(1.0, economy.getXp(player), "10 salary * xpPerCoin 0.1 = 1 xp");
        });
    }

    @Test
    void higherLevelPlayersGetAHigherSalary() throws Exception {
        withSalary(defaultConfig(), 5, (salaryManager, economy, server, player, serverPlayer) -> {
            // 28,600 coins earned * xpPerCoin 0.1 = 2,860 xp, exactly the cumulative
            // requirement for level 10 with levelCurveBaseXp=20 (20 * sum(fib(1..10))).
            economy.giveEarned(player, 28_600.0);
            double balanceAfterSetup = economy.getBalance(player);

            salaryManager.tick(server); // baseline day
            when(server.overworld().getGameTime()).thenReturn(6L * 24000L);
            salaryManager.tick(server);

            double payout = economy.getBalance(player) - balanceAfterSetup;
            assertEquals(255.0, payout, "level 10 of 20 halfway between base 10 and max 500");
        });
    }
}
