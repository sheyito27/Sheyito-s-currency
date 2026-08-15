package com.sheyito.economicmaster.rent;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.config.RentConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Covers RentManager's billing pass: baseline seeding on first sighting, the 7-day cadence, and
 * that only PROFIT (not total balance) ever gets taxed. */
class RentManagerTest {

    private interface WithRent {
        void run(RentManager rent, EconomyManager economy, MinecraftServer server) throws Exception;
    }

    private void withRent(int intervalGameDays, long currentGameDay, WithRent test) throws Exception {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;
        general.startingBalance = 0.0;
        RentConfig config = new RentConfig();
        config.enabled = true;
        config.intervalGameDays = intervalGameDays;
        config.profitBrackets = List.of(
                new RentConfig.Bracket(0, 0.10),
                new RentConfig.Bracket(10_000, 0.20),
                new RentConfig.Bracket(100_000, 0.30),
                new RentConfig.Bracket(1_000_000, 0.40));

        EconomyManager economy = EconomyManager.createForTesting();
        EconomyManager.installForTesting(economy);
        RentManager rent = RentManager.createForTesting();
        RentManager.installForTesting(rent);

        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        ServerLevel overworld = mock(ServerLevel.class);
        when(server.getPlayerList()).thenReturn(playerList);
        when(server.overworld()).thenReturn(overworld);
        when(overworld.getGameTime()).thenReturn(currentGameDay * 24000L);
        when(playerList.getPlayer(org.mockito.ArgumentMatchers.any(UUID.class))).thenReturn(null);

        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            mocked.when(ConfigManager::rent).thenReturn(config);
            test.run(rent, economy, server);
        } finally {
            EconomyManager.installForTesting(null);
            RentManager.installForTesting(null);
        }
    }

    @Test
    void firstSightingSeedsABaselineWithoutCharging() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 5_000.0);

            rent.processDueRent(server);

            assertEquals(5_000.0, economy.getBalance(uuid), "no charge on the very first pass - nothing to compare profit against yet");
        });
    }

    @Test
    void noChargeBeforeTheIntervalElapses() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 5_000.0);
            rent.processDueRent(server); // seeds baseline at day 0

            economy.give(uuid, 1_000.0); // now 6,000, "profit" of 1,000 - but only 0 days have passed
            rent.processDueRent(server);

            assertEquals(6_000.0, economy.getBalance(uuid), "still day 0 - not due for another 7 days");
        });
    }

    @Test
    void chargesTheBracketPercentOnlyOnProfitOnceTheIntervalElapses() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 5_000.0);
            rent.processDueRent(server); // baseline = 5,000 at day 0

            economy.give(uuid, 5_000.0); // balance now 10,000 -> profit 5,000, bracket 10% -> tax 500
            when(server.overworld().getGameTime()).thenReturn(7L * 24000L);
            rent.processDueRent(server);

            assertEquals(9_500.0, economy.getBalance(uuid), "10,000 - 500 (10% of the 5,000 profit)");
        });
    }

    @Test
    void aLossInThePeriodIsNeverTaxedAndResetsTheBaselineDownward() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 10_000.0);
            rent.processDueRent(server); // baseline = 10,000 at day 0

            economy.take(uuid, 3_000.0); // balance now 7,000 - a loss, not a profit
            when(server.overworld().getGameTime()).thenReturn(7L * 24000L);
            rent.processDueRent(server);

            assertEquals(7_000.0, economy.getBalance(uuid), "a loss is never taxed - balance untouched");

            // next period: any recovery back up counts as fresh profit against the lower baseline.
            economy.give(uuid, 1_000.0); // 8,000, profit of 1,000 against the 7,000 baseline
            when(server.overworld().getGameTime()).thenReturn(14L * 24000L);
            rent.processDueRent(server);

            assertEquals(7_900.0, economy.getBalance(uuid), "8,000 - 100 (10% of the 1,000 profit)");
        });
    }

    @Test
    void forceProcessSeedsABaselineOnFirstSightingJustLikeTheNormalPass() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 5_000.0);

            rent.forceProcess(server, uuid);

            assertEquals(5_000.0, economy.getBalance(uuid), "first sighting via forceProcess only seeds a baseline too");
        });
    }

    @Test
    void forceProcessChargesImmediatelyIgnoringTheInterval() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 5_000.0);
            rent.forceProcess(server, uuid); // seeds baseline = 5,000 at day 0

            economy.give(uuid, 5_000.0); // balance now 10,000 -> profit 5,000, bracket 10% -> tax 500
            // still day 0 - a normal processDueRent pass would skip this, forceProcess must not
            rent.forceProcess(server, uuid);

            assertEquals(9_500.0, economy.getBalance(uuid), "charged immediately despite 0 days having elapsed");
        });
    }

    @Test
    void higherBracketAppliesForLargerProfits() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 100.0);
            rent.processDueRent(server); // baseline = 100 at day 0

            economy.give(uuid, 149_900.0); // balance now 150,000 -> profit 149,900, bracket 100K -> 30%
            when(server.overworld().getGameTime()).thenReturn(7L * 24000L);
            rent.processDueRent(server);

            assertEquals(150_000.0 - 149_900.0 * 0.30, economy.getBalance(uuid), 0.001);
        });
    }
}
