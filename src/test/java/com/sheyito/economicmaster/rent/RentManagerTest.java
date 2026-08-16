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

/** Covers RentManager's billing pass: gains accumulate gross (via EconomyManager.give(), never
 * netted against spending), a brand new record is immediately due, and charging always resets
 * the accumulator and the 7-day clock. */
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
    void aBrandNewRecordIsImmediatelyDueAndGetsCharged() throws Exception {
        // No pre-existing balance to worry about double-counting - gains only ever accumulate
        // from give() onward, so there is no reason to wait a full interval before the first
        // bill the way the old net-worth-snapshot design needed to.
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 1_000.0); // tracked as a gain automatically by EconomyManager.give()

            rent.processDueRent(server);

            assertEquals(1_000.0 - 100.0, economy.getBalance(uuid), "10% of the 1,000 gained, charged on the very first pass");
        });
    }

    @Test
    void noChargeAgainBeforeTheIntervalElapses() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 1_000.0);
            rent.processDueRent(server); // charges 100, resets the accumulator, lastRentDay = 0

            economy.give(uuid, 500.0); // still day 0 - not due again yet
            rent.processDueRent(server);

            assertEquals(1_000.0 - 100.0 + 500.0, economy.getBalance(uuid), "not due yet, nothing charged");
        });
    }

    @Test
    void chargesTheBracketPercentOfAccumulatedGainsOnceTheIntervalElapses() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 1_000.0);
            rent.processDueRent(server); // charges 100, resets, lastRentDay = 0

            economy.give(uuid, 5_000.0); // 5,000 earned this period -> bracket 10% -> tax 500
            when(server.overworld().getGameTime()).thenReturn(7L * 24000L);
            rent.processDueRent(server);

            assertEquals(1_000.0 - 100.0 + 5_000.0 - 500.0, economy.getBalance(uuid));
        });
    }

    @Test
    void losingMoneyInThePeriodDoesNotOffsetGainsAtAll() throws Exception {
        // The exact scenario the user described: earn some money, separately lose more than
        // that in the same period - still taxed on what was earned, even though the player is
        // down overall. A loss is spending, unrelated to this tax.
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 5_000.0);
            rent.processDueRent(server); // charges 500 (10% of 5,000), balance now 4,500, accumulator cleared

            economy.give(uuid, 3_000.0); // earned 3,000 this period
            economy.take(uuid, 4_000.0); // separately lost 4,000 - more than was earned, net down overall
            when(server.overworld().getGameTime()).thenReturn(7L * 24000L);
            rent.processDueRent(server); // taxed on the 3,000 earned (10% = 300); the 4,000 loss is irrelevant

            assertEquals(4_500.0 + 3_000.0 - 4_000.0 - 300.0, economy.getBalance(uuid), 0.001);
        });
    }

    @Test
    void chargingResetsAccumulatedGainsSoTheNextPeriodStartsFromZero() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 15_000.0); // bracket 20% (>= 10,000) -> tax 3,000
            rent.processDueRent(server);
            assertEquals(15_000.0 - 3_000.0, economy.getBalance(uuid));

            // second period: only the NEW 500 earned counts, not the whole balance again.
            economy.give(uuid, 500.0);
            when(server.overworld().getGameTime()).thenReturn(7L * 24000L);
            rent.processDueRent(server);

            assertEquals(15_000.0 - 3_000.0 + 500.0 - 50.0, economy.getBalance(uuid), 0.001);
        });
    }

    @Test
    void chargingTheTaxCanPushTheBalanceNegativeOnPurpose() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 5_000.0);
            rent.processDueRent(server); // charges 500 (10% of 5,000), balance now 4,500

            economy.give(uuid, 1_000.0); // fresh gain of 1,000 -> next tax will be 100 (10%)
            economy.charge(uuid, 5_490.0); // spend almost everything right before the bill lands - balance now 10
            when(server.overworld().getGameTime()).thenReturn(7L * 24000L);
            rent.processDueRent(server); // still charges the full 100 regardless - balance goes negative

            assertEquals(-90.0, economy.getBalance(uuid), 0.001,
                    "unlike every other sink in this mod, this one uses charge() not take() - it's meant to be able to cause banca rota");
        });
    }

    @Test
    void forceProcessIsANoOpWithNoTrackedGainsAtAll() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();

            rent.forceProcess(server, uuid);

            assertEquals(0.0, economy.getBalance(uuid));
        });
    }

    @Test
    void forceProcessChargesImmediatelyIgnoringTheInterval() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 1_000.0);
            rent.forceProcess(server, uuid); // charges 100, resets the accumulator
            // still day 0 - a normal processDueRent pass would refuse this, forceProcess must not

            economy.give(uuid, 2_000.0); // fresh gain since the last force
            rent.forceProcess(server, uuid); // charges 200 (10% of 2,000)

            assertEquals(1_000.0 - 100.0 + 2_000.0 - 200.0, economy.getBalance(uuid));
        });
    }

    @Test
    void higherBracketAppliesForLargerGains() throws Exception {
        withRent(7, 0, (rent, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            economy.give(uuid, 150_000.0); // bracket 100K (>= 100,000) -> 30%

            rent.processDueRent(server);

            assertEquals(150_000.0 - 45_000.0, economy.getBalance(uuid), 0.001);
        });
    }
}
