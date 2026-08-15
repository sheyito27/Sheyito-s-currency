package com.sheyito.economicmaster.embargo;

import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.auction.AuctionPoolManager;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.EmbargoConfig;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Covers the two halves of the embargo: the real-time grace-period countdown (paused while
 * offline, idempotent while already active) and the community vote on which seized item goes to
 * the auction pool (secret, changeable, closes only once both conditions are met). */
class EmbargoManagerTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private interface WithEmbargo {
        void run(EmbargoManager embargo, EconomyManager economy, MinecraftServer server) throws Exception;
    }

    private void withEmbargo(int graceSeconds, int minVoters, int minVoteDays, WithEmbargo test) throws Exception {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;
        general.startingBalance = 0.0;
        EmbargoConfig embargoConfig = new EmbargoConfig();
        embargoConfig.enabled = true;
        embargoConfig.graceSeconds = graceSeconds;
        embargoConfig.minVotersToClose = minVoters;
        embargoConfig.minVoteGameDays = minVoteDays;

        EconomyManager economy = EconomyManager.createForTesting();
        EconomyManager.installForTesting(economy);
        AuctionPoolManager pool = AuctionPoolManager.createForTesting();
        AuctionPoolManager.installForTesting(pool);
        EmbargoManager embargo = EmbargoManager.createForTesting();
        EmbargoManager.installForTesting(embargo);

        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        ServerLevel overworld = mock(ServerLevel.class);
        when(server.getPlayerList()).thenReturn(playerList);
        when(server.overworld()).thenReturn(overworld);
        when(overworld.getGameTime()).thenReturn(0L);
        when(server.getTickCount()).thenReturn(0);
        when(playerList.getPlayers()).thenReturn(List.of());

        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            mocked.when(ConfigManager::embargo).thenReturn(embargoConfig);
            test.run(embargo, economy, server);
        } finally {
            EconomyManager.installForTesting(null);
            AuctionPoolManager.installForTesting(null);
            EmbargoManager.installForTesting(null);
        }
    }

    /** A player with no equipment and an inventory holding exactly {@code items}, positioned at
     * indices 0..N - enough to drive seizure without needing a real Inventory/ServerPlayer. */
    private static ServerPlayer mockPlayer(UUID uuid, ItemStack... items) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContainerSize()).thenReturn(items.length);
        for (int i = 0; i < items.length; i++) {
            when(inventory.getItem(i)).thenReturn(items[i]);
        }
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(uuid);
        when(player.getInventory()).thenReturn(inventory);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            when(player.getItemBySlot(slot)).thenReturn(ItemStack.EMPTY);
        }
        return player;
    }

    private static void tick(EmbargoManager embargo, MinecraftServer server, int times) {
        for (int i = 0; i < times; i++) {
            embargo.tickGrace(server);
        }
    }

    @Test
    void balanceGoingNegativeStartsAGracePeriod() throws Exception {
        withEmbargo(30, 2, 2, (embargo, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            assertFalse(embargo.isInGracePeriod(uuid));

            economy.setBalance(uuid, -10.0);

            assertTrue(embargo.isInGracePeriod(uuid), "EconomyManager.setBalance must notify EmbargoManager on the >=0 to <0 crossing");
        });
    }

    @Test
    void aSecondNegativeCrossingWhileAlreadyInGraceDoesNotResetTheCountdown() throws Exception {
        withEmbargo(2, 2, 2, (embargo, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            ServerPlayer player = mockPlayer(uuid);
            when(server.getPlayerList().getPlayer(uuid)).thenReturn(player);
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(player));

            economy.setBalance(uuid, -10.0);
            tick(embargo, server, 20); // 1 of 2 configured seconds elapsed

            embargo.onBalanceWentNegative(uuid); // redundant call - must be a no-op, not a reset
            tick(embargo, server, 20); // if it had reset, this would only bring elapsed back to 1s, not 2s

            assertFalse(embargo.isInGracePeriod(uuid), "2 real seconds must have elapsed by now and triggered the seizure");
            assertEquals(0.0, economy.getBalance(uuid));
        });
    }

    @Test
    void gracePeriodClearsWithoutSeizureIfBalanceRecoversInTime() throws Exception {
        withEmbargo(2, 2, 2, (embargo, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            ServerPlayer player = mockPlayer(uuid, new ItemStack(Items.IRON_SWORD));
            when(server.getPlayerList().getPlayer(uuid)).thenReturn(player);

            economy.setBalance(uuid, -10.0);
            economy.setBalance(uuid, 5.0);
            tick(embargo, server, 20);

            assertFalse(embargo.isInGracePeriod(uuid));
            assertEquals(5.0, economy.getBalance(uuid), "paying off in time must not touch the balance beyond what they already paid");
        });
    }

    @Test
    void gracePeriodIsPausedWhileThePlayerIsOffline() throws Exception {
        withEmbargo(1, 2, 2, (embargo, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            // No stub for getPlayer(uuid) -> null -> treated as offline.

            economy.setBalance(uuid, -10.0);
            tick(embargo, server, 200);

            assertTrue(embargo.isInGracePeriod(uuid), "must never expire while the player was never online to see it");
            assertEquals(-10.0, economy.getBalance(uuid));
        });
    }

    @Test
    void expiryExecutesSeizureAndOpensAVoteExcludingTheVictim() throws Exception {
        withEmbargo(1, 2, 2, (embargo, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bystander = UUID.randomUUID();
            ServerPlayer player = mockPlayer(victim, new ItemStack(Items.IRON_SWORD));
            when(server.getPlayerList().getPlayer(victim)).thenReturn(player);
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(player));

            economy.setBalance(victim, -10.0);
            tick(embargo, server, 20);

            assertFalse(embargo.isInGracePeriod(victim));
            assertEquals(0.0, economy.getBalance(victim), "no vuelta atras - balance lands at exactly 0");
            assertTrue(embargo.openVoteFor(bystander).isPresent());
            assertTrue(embargo.openVoteFor(victim).isEmpty(), "the victim cannot vote in their own auction");
        });
    }

    @Test
    void expiryWithNothingSeizableOpensNoVote() throws Exception {
        withEmbargo(1, 2, 2, (embargo, economy, server) -> {
            UUID uuid = UUID.randomUUID();
            ServerPlayer player = mockPlayer(uuid, new ItemStack(Items.DIRT, 64));
            when(server.getPlayerList().getPlayer(uuid)).thenReturn(player);
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(player));

            economy.setBalance(uuid, -10.0);
            tick(embargo, server, 20);

            assertFalse(embargo.isInGracePeriod(uuid));
            assertTrue(embargo.openVoteFor(UUID.randomUUID()).isEmpty(), "nothing seizable means nothing to vote on");
        });
    }

    @Test
    void castVoteRecordsAndCanBeChangedButNotByTheVictim() throws Exception {
        withEmbargo(1, 2, 2, (embargo, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID voter = UUID.randomUUID();
            ServerPlayer player = mockPlayer(victim, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.IRON_PICKAXE));
            when(server.getPlayerList().getPlayer(victim)).thenReturn(player);
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(player));
            economy.setBalance(victim, -10.0);
            tick(embargo, server, 20);
            long voteId = embargo.openVoteFor(voter).orElseThrow();

            assertNull(embargo.voteOf(voteId, voter));
            embargo.castVote(voteId, voter, 0, server);
            assertEquals(0, embargo.voteOf(voteId, voter));
            embargo.castVote(voteId, voter, 1, server);
            assertEquals(1, embargo.voteOf(voteId, voter), "casting again must overwrite the previous vote");

            embargo.castVote(voteId, victim, 0, server);
            assertNull(embargo.voteOf(voteId, victim), "the victim's own vote attempt must be ignored");
        });
    }

    @Test
    void votingDoesNotCloseWithoutBothConditions() throws Exception {
        withEmbargo(1, 2, 2, (embargo, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID voterA = UUID.randomUUID();
            ServerPlayer player = mockPlayer(victim, new ItemStack(Items.IRON_SWORD));
            when(server.getPlayerList().getPlayer(victim)).thenReturn(player);
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(player));
            economy.setBalance(victim, -10.0);
            tick(embargo, server, 20);
            long voteId = embargo.openVoteFor(voterA).orElseThrow();

            embargo.castVote(voteId, voterA, 0, server);
            when(server.overworld().getGameTime()).thenReturn(5L * 24000L);
            embargo.tickVoteClosing(server);
            assertTrue(embargo.isVoteActive(voteId), "only 1 of 2 required voters - must not close despite enough days");

            when(server.overworld().getGameTime()).thenReturn(0L);
            UUID voterB = UUID.randomUUID();
            embargo.castVote(voteId, voterB, 0, server);
            embargo.tickVoteClosing(server);
            assertTrue(embargo.isVoteActive(voteId), "2 voters now, but 0 game days elapsed - must not close yet");
        });
    }

    @Test
    void votingClosesOnceBothConditionsAreMetAndMovesTheWinnerToThePool() throws Exception {
        withEmbargo(1, 2, 2, (embargo, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID voterA = UUID.randomUUID();
            UUID voterB = UUID.randomUUID();
            ServerPlayer player = mockPlayer(victim, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.IRON_PICKAXE));
            when(server.getPlayerList().getPlayer(victim)).thenReturn(player);
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(player));
            economy.setBalance(victim, -10.0);
            tick(embargo, server, 20);
            long voteId = embargo.openVoteFor(voterA).orElseThrow();

            embargo.castVote(voteId, voterA, 0, server);
            embargo.castVote(voteId, voterB, 0, server);
            when(server.overworld().getGameTime()).thenReturn(2L * 24000L);
            embargo.tickVoteClosing(server);

            assertFalse(embargo.isVoteActive(voteId));
            List<AuctionPoolManager.PooledItem> pooled = AuctionPoolManager.get().list();
            assertEquals(1, pooled.size(), "exactly one item - the winner - goes to the pool");
            assertEquals(Items.IRON_SWORD, pooled.get(0).stack().getItem());
        });
    }

    @Test
    void tiedCandidatesAreWonByWhicheverReachedThatVoteCountFirst() throws Exception {
        withEmbargo(1, 2, 2, (embargo, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID voterA = UUID.randomUUID();
            UUID voterB = UUID.randomUUID();
            ServerPlayer player = mockPlayer(victim, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.IRON_PICKAXE));
            when(server.getPlayerList().getPlayer(victim)).thenReturn(player);
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(player));
            economy.setBalance(victim, -10.0);
            tick(embargo, server, 20);
            long voteId = embargo.openVoteFor(voterA).orElseThrow();

            when(server.getTickCount()).thenReturn(100);
            embargo.castVote(voteId, voterA, 0, server); // index 0 reaches 1 vote at tick 100
            when(server.getTickCount()).thenReturn(200);
            embargo.castVote(voteId, voterB, 1, server); // index 1 reaches 1 vote at tick 200 - tied at 1 each

            when(server.overworld().getGameTime()).thenReturn(2L * 24000L);
            embargo.tickVoteClosing(server);

            assertEquals(Items.IRON_SWORD, AuctionPoolManager.get().list().get(0).stack().getItem(),
                    "index 0 (the sword) reached its final count first, so it wins the tie");
        });
    }

    @Test
    void nonWinningItemsAreReturnedToAnOnlineVictimWhenVotingCloses() throws Exception {
        withEmbargo(1, 2, 2, (embargo, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID voterA = UUID.randomUUID();
            UUID voterB = UUID.randomUUID();
            ServerPlayer player = mockPlayer(victim, new ItemStack(Items.IRON_SWORD), new ItemStack(Items.IRON_PICKAXE));
            when(server.getPlayerList().getPlayer(victim)).thenReturn(player);
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(player));
            economy.setBalance(victim, -10.0);
            tick(embargo, server, 20);
            long voteId = embargo.openVoteFor(voterA).orElseThrow();

            embargo.castVote(voteId, voterA, 0, server);
            embargo.castVote(voteId, voterB, 0, server);
            when(server.overworld().getGameTime()).thenReturn(2L * 24000L);
            embargo.tickVoteClosing(server);

            org.mockito.Mockito.verify(player.getInventory()).placeItemBackInInventory(
                    org.mockito.ArgumentMatchers.argThat(stack -> stack.getItem() == Items.IRON_PICKAXE));
        });
    }
}
