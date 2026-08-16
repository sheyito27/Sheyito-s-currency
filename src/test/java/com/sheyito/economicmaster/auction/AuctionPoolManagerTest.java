package com.sheyito.economicmaster.auction;

import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.EmbargoConfig;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers the pool's FIFO storage plus the bidding auction on whichever item sits at the front -
 * see {@code EmbargoDeudas.md}'s "La subasta con pujas" section for the design. */
class AuctionPoolManagerTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private interface WithPool {
        void run(AuctionPoolManager pool, EconomyManager economy, MinecraftServer server) throws Exception;
    }

    private void withPool(int auctionDurationGameDays, WithPool test) throws Exception {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;
        general.startingBalance = 0.0;
        EmbargoConfig embargoConfig = new EmbargoConfig();
        embargoConfig.enabled = true;
        embargoConfig.auctionDurationGameDays = auctionDurationGameDays;
        embargoConfig.bidIncrements = List.of(10.0, 100.0, 1000.0);

        EconomyManager economy = EconomyManager.createForTesting();
        EconomyManager.installForTesting(economy);
        AuctionPoolManager pool = AuctionPoolManager.createForTesting();
        AuctionPoolManager.installForTesting(pool);

        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        ServerLevel overworld = mock(ServerLevel.class);
        when(server.getPlayerList()).thenReturn(playerList);
        when(server.overworld()).thenReturn(overworld);
        when(overworld.getGameTime()).thenReturn(0L);
        when(playerList.getPlayers()).thenReturn(List.of());

        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            mocked.when(ConfigManager::embargo).thenReturn(embargoConfig);
            test.run(pool, economy, server);
        } finally {
            EconomyManager.installForTesting(null);
            AuctionPoolManager.installForTesting(null);
        }
    }

    private static ServerPlayer mockOnlinePlayer(UUID uuid) {
        Inventory inventory = mock(Inventory.class);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(uuid);
        when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    @Test
    void newPoolIsEmpty() throws Exception {
        withPool(3, (pool, economy, server) -> {
            assertTrue(pool.list().isEmpty());
            assertTrue(pool.retrieveNext(server).isEmpty());
            assertTrue(pool.currentAuctionItem().isEmpty());
        });
    }

    @Test
    void addedItemsShowUpInListAndOpenAFreshAuctionIfThePoolWasEmpty() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();

            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 5L);

            List<AuctionPoolManager.PooledItem> items = pool.list();
            assertEquals(1, items.size());
            assertEquals(Items.DIAMOND_SWORD, items.get(0).stack().getItem());
            assertEquals(victim, items.get(0).seizedFromUuid());
            assertEquals("Fulano", items.get(0).seizedFromName());
            assertEquals(5L, items.get(0).addedAtGameDay());

            assertTrue(pool.currentAuctionItem().isPresent(), "a fresh auction opens automatically");
            assertEquals(0.0, pool.currentHighestBid());
            assertTrue(pool.currentHighestBidder().isEmpty());
        });
    }

    @Test
    void aSecondAddedItemJustQueuesBehindTheOneAlreadyUpForAuction() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 1L);
            pool.add(new ItemStack(Items.NETHERITE_AXE), victim, "Fulano", 2L);

            assertEquals(Items.DIAMOND_SWORD, pool.currentAuctionItem().orElseThrow().stack().getItem(),
                    "the first item stays the one up for auction, the second just queues");
        });
    }

    @Test
    void validBidBecomesTheNewHighestAndTakesTheMoneyImmediately() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.give(bidder, 1000.0);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);

            AuctionPoolManager.BidResult result = pool.placeBid(bidder, 100.0);

            assertEquals(AuctionPoolManager.BidResult.SUCCESS, result);
            assertEquals(100.0, pool.currentHighestBid());
            assertEquals(bidder, pool.currentHighestBidder().orElseThrow());
            assertEquals(900.0, economy.getBalance(bidder), "the bid is escrowed - taken immediately");
        });
    }

    @Test
    void bidThatDoesNotBeatTheCurrentHighestIsRejectedWithoutTouchingMoney() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID firstBidder = UUID.randomUUID();
            UUID secondBidder = UUID.randomUUID();
            economy.give(firstBidder, 1000.0);
            economy.give(secondBidder, 1000.0);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            pool.placeBid(firstBidder, 100.0);

            AuctionPoolManager.BidResult result = pool.placeBid(secondBidder, 100.0);

            assertEquals(AuctionPoolManager.BidResult.TOO_LOW, result);
            assertEquals(1000.0, economy.getBalance(secondBidder), "a rejected bid never touches the bidder's money");
            assertEquals(firstBidder, pool.currentHighestBidder().orElseThrow());
        });
    }

    @Test
    void bidWithoutEnoughBalanceIsRejected() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.give(bidder, 50.0);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);

            AuctionPoolManager.BidResult result = pool.placeBid(bidder, 100.0);

            assertEquals(AuctionPoolManager.BidResult.INSUFFICIENT_FUNDS, result);
            assertEquals(50.0, economy.getBalance(bidder));
            assertTrue(pool.currentHighestBidder().isEmpty());
        });
    }

    @Test
    void theOriginalVictimCannotBidOnTheirOwnItem() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            economy.give(victim, 1000.0);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);

            AuctionPoolManager.BidResult result = pool.placeBid(victim, 100.0);

            assertEquals(AuctionPoolManager.BidResult.CANNOT_BID_ON_OWN_ITEM, result);
            assertEquals(1000.0, economy.getBalance(victim));
        });
    }

    @Test
    void outbiddingRefundsThePreviousHighestBidderInFull() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID firstBidder = UUID.randomUUID();
            UUID secondBidder = UUID.randomUUID();
            economy.give(firstBidder, 1000.0);
            economy.give(secondBidder, 1000.0);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            pool.placeBid(firstBidder, 100.0);

            AuctionPoolManager.BidResult result = pool.placeBid(secondBidder, 200.0);

            assertEquals(AuctionPoolManager.BidResult.SUCCESS, result);
            assertEquals(1000.0, economy.getBalance(firstBidder), "outbid - refunded in full");
            assertEquals(800.0, economy.getBalance(secondBidder));
            assertEquals(secondBidder, pool.currentHighestBidder().orElseThrow());
            assertEquals(200.0, pool.currentHighestBid());
        });
    }

    @Test
    void tickAuctionClosingDoesNothingBeforeTheDurationElapses() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.give(bidder, 1000.0);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            pool.placeBid(bidder, 100.0);

            when(server.overworld().getGameTime()).thenReturn(2L * 24000L);
            pool.tickAuctionClosing(server);

            assertEquals(1, pool.list().size(), "still open - only 2 of 3 required days elapsed");
            assertEquals(100.0, pool.currentHighestBid());
        });
    }

    @Test
    void closingWithAWinnerDeliversTheItemAndBurnsTheEscrowedMoney() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.give(bidder, 1000.0);
            ServerPlayer winnerPlayer = mockOnlinePlayer(bidder);
            when(server.getPlayerList().getPlayer(bidder)).thenReturn(winnerPlayer);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            pool.placeBid(bidder, 100.0);

            when(server.overworld().getGameTime()).thenReturn(3L * 24000L);
            pool.tickAuctionClosing(server);

            assertTrue(pool.list().isEmpty(), "the sold item leaves the pool");
            verify(winnerPlayer.getInventory()).placeItemBackInInventory(
                    org.mockito.ArgumentMatchers.argThat(stack -> stack.getItem() == Items.DIAMOND_SWORD));
            assertEquals(900.0, economy.getBalance(bidder), "the escrowed 100 stays taken - never given to anyone, which is the burn");
        });
    }

    @Test
    void closingWithAWinnerOpensAFreshAuctionOnTheNextQueuedItem() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.give(bidder, 1000.0);
            ServerPlayer winnerPlayer = mockOnlinePlayer(bidder);
            when(server.getPlayerList().getPlayer(bidder)).thenReturn(winnerPlayer);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            pool.add(new ItemStack(Items.NETHERITE_AXE), victim, "Fulano", 0L);
            pool.placeBid(bidder, 100.0);

            when(server.overworld().getGameTime()).thenReturn(3L * 24000L);
            pool.tickAuctionClosing(server);

            assertEquals(Items.NETHERITE_AXE, pool.currentAuctionItem().orElseThrow().stack().getItem());
            assertEquals(0.0, pool.currentHighestBid(), "a brand new auction, no carried-over bid");
            assertTrue(pool.currentHighestBidder().isEmpty());
        });
    }

    @Test
    void closingWithNoBidsSendsTheItemToTheBackOfTheQueueInsteadOfDestroyingIt() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            pool.add(new ItemStack(Items.NETHERITE_AXE), victim, "Fulano", 0L);

            when(server.overworld().getGameTime()).thenReturn(3L * 24000L);
            pool.tickAuctionClosing(server);

            assertEquals(2, pool.list().size(), "nothing is destroyed just for going unsold");
            assertEquals(Items.NETHERITE_AXE, pool.currentAuctionItem().orElseThrow().stack().getItem(),
                    "the next item in line gets its turn now");
            List<AuctionPoolManager.PooledItem> items = pool.list();
            assertEquals(Items.DIAMOND_SWORD, items.get(1).stack().getItem(), "the unsold sword moved to the back");
        });
    }

    @Test
    void winnerOfflineAtCloseTimeGetsTheItemDeliveredOnNextLogin() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.give(bidder, 1000.0);
            // No stub for getPlayer(bidder) -> null -> offline.
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            pool.placeBid(bidder, 100.0);

            when(server.overworld().getGameTime()).thenReturn(3L * 24000L);
            pool.tickAuctionClosing(server);

            ServerPlayer loggingIn = mockOnlinePlayer(bidder);
            pool.deliverPending(loggingIn);

            verify(loggingIn.getInventory()).placeItemBackInInventory(
                    org.mockito.ArgumentMatchers.argThat(stack -> stack.getItem() == Items.DIAMOND_SWORD));
        });
    }

    @Test
    void retrieveNextPopsInFifoOrderAndRemovesFromThePool() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 1L);
            pool.add(new ItemStack(Items.NETHERITE_AXE), victim, "Fulano", 2L);

            Optional<AuctionPoolManager.PooledItem> first = pool.retrieveNext(server);

            assertTrue(first.isPresent());
            assertEquals(Items.DIAMOND_SWORD, first.get().stack().getItem());
            assertEquals(1, pool.list().size());
            assertEquals(Items.NETHERITE_AXE, pool.list().get(0).stack().getItem());
        });
    }

    @Test
    void withdrawRefundsAnActiveBidderBeforeTakingTheItemOut() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.give(bidder, 1000.0);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            pool.placeBid(bidder, 100.0);

            Optional<AuctionPoolManager.PooledItem> withdrawn = pool.retrieveNext(server);

            assertTrue(withdrawn.isPresent());
            assertEquals(1000.0, economy.getBalance(bidder), "no money left stranded with neither the item nor a refund");
        });
    }

    @Test
    void withdrawWithNoActiveBidDoesNotTouchAnyBalance() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);

            assertTrue(pool.retrieveNext(server).isPresent());
        });
    }
}
