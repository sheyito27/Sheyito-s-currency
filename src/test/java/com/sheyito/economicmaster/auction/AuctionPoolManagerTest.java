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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers the pool's storage plus the bidding auction on whichever item was picked via
 * {@link AuctionPoolManager#startAuction} - the only way one ever starts, confirmed with the user
 * against an earlier "opens automatically the moment the pool isn't empty" design (that's what the
 * "puesto de subastas" villager multiblock is for - see {@code embargo.AuctionStandListener}, not
 * unit-testable here since it needs a real Level). See {@code EmbargoDeudas.md}'s "La subasta con
 * pujas" section for the full design. */
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

    /** Starts an auction on whichever item is first in the list right now - convenience for tests
     * that only care about bidding/closing, not about which item got picked. */
    private static void startFirst(AuctionPoolManager pool, long day) {
        AuctionPoolManager.PooledItem item = pool.list().get(0);
        assertEquals(AuctionPoolManager.StartResult.SUCCESS, pool.startAuction(item, day));
    }

    @Test
    void newPoolIsEmpty() throws Exception {
        withPool(3, (pool, economy, server) -> {
            assertTrue(pool.list().isEmpty());
            assertTrue(pool.retrieveNext().isEmpty());
            assertTrue(pool.currentAuctionItem().isEmpty());
        });
    }

    @Test
    void addedItemsShowUpInListButNothingStartsOnItsOwn() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();

            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 5L);

            List<AuctionPoolManager.PooledItem> items = pool.list();
            assertEquals(1, items.size());
            assertEquals(Items.DIAMOND_SWORD, items.get(0).stack().getItem());
            assertEquals(victim, items.get(0).seizedFromUuid());
            assertEquals("Fulano", items.get(0).seizedFromName());
            assertEquals(5L, items.get(0).addedAtGameDay());

            assertTrue(pool.currentAuctionItem().isEmpty(),
                    "nothing is up for bid until someone explicitly starts it via startAuction");
        });
    }

    @Test
    void startAuctionOpensBiddingOnWhicheverItemIsChosen() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            pool.add(new ItemStack(Items.NETHERITE_AXE), victim, "Fulano", 0L);
            AuctionPoolManager.PooledItem axe = pool.list().get(1);

            AuctionPoolManager.StartResult result = pool.startAuction(axe, 0L);

            assertEquals(AuctionPoolManager.StartResult.SUCCESS, result);
            assertEquals(Items.NETHERITE_AXE, pool.currentAuctionItem().orElseThrow().stack().getItem(),
                    "the chosen item becomes the one up for auction, regardless of its original position");
            assertEquals(0.0, pool.currentHighestBid());
            assertTrue(pool.currentHighestBidder().isEmpty());
        });
    }

    @Test
    void startAuctionMovesTheChosenItemToTheFrontWithoutLosingTheRest() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            pool.add(new ItemStack(Items.NETHERITE_AXE), victim, "Fulano", 0L);
            pool.add(new ItemStack(Items.IRON_PICKAXE), victim, "Fulano", 0L);
            AuctionPoolManager.PooledItem pickaxe = pool.list().get(2);

            pool.startAuction(pickaxe, 0L);

            List<AuctionPoolManager.PooledItem> items = pool.list();
            assertEquals(3, items.size(), "nothing gets lost, just reordered");
            assertEquals(Items.IRON_PICKAXE, items.get(0).stack().getItem());
            assertEquals(Items.DIAMOND_SWORD, items.get(1).stack().getItem());
            assertEquals(Items.NETHERITE_AXE, items.get(2).stack().getItem());
        });
    }

    @Test
    void startAuctionFailsIfOneIsAlreadyActive() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            pool.add(new ItemStack(Items.NETHERITE_AXE), victim, "Fulano", 0L);
            AuctionPoolManager.PooledItem sword = pool.list().get(0);
            AuctionPoolManager.PooledItem axe = pool.list().get(1);
            pool.startAuction(sword, 0L);

            AuctionPoolManager.StartResult result = pool.startAuction(axe, 0L);

            assertEquals(AuctionPoolManager.StartResult.ALREADY_ACTIVE, result);
            assertEquals(Items.DIAMOND_SWORD, pool.currentAuctionItem().orElseThrow().stack().getItem(),
                    "the already-active auction is untouched");
        });
    }

    @Test
    void startAuctionFailsIfTheChosenItemIsNoLongerInThePool() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            AuctionPoolManager.PooledItem notPooled = new AuctionPoolManager.PooledItem(
                    new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);

            AuctionPoolManager.StartResult result = pool.startAuction(notPooled, 0L);

            assertEquals(AuctionPoolManager.StartResult.ITEM_NOT_FOUND, result);
        });
    }

    @Test
    void validBidBecomesTheNewHighestAndTakesTheMoneyImmediately() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.give(bidder, 1000.0);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            startFirst(pool, 0L);

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
            startFirst(pool, 0L);
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
            startFirst(pool, 0L);

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
            startFirst(pool, 0L);

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
            startFirst(pool, 0L);
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
            startFirst(pool, 0L);
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
            startFirst(pool, 0L);
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
    void closingWithAWinnerDoesNotAutoOpenTheNextQueuedItem() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.give(bidder, 1000.0);
            ServerPlayer bidderPlayer = mockOnlinePlayer(bidder);
            when(server.getPlayerList().getPlayer(bidder)).thenReturn(bidderPlayer);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            pool.add(new ItemStack(Items.NETHERITE_AXE), victim, "Fulano", 0L);
            startFirst(pool, 0L);
            pool.placeBid(bidder, 100.0);

            when(server.overworld().getGameTime()).thenReturn(3L * 24000L);
            pool.tickAuctionClosing(server);

            assertTrue(pool.currentAuctionItem().isEmpty(),
                    "nothing reopens on its own - someone has to pick the axe at the auction stand");
            assertEquals(1, pool.list().size());
            assertEquals(Items.NETHERITE_AXE, pool.list().get(0).stack().getItem(), "still waiting in the pool");
        });
    }

    @Test
    void closingWithNoBidsSendsTheItemToTheBackOfTheQueueInsteadOfDestroyingIt() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            pool.add(new ItemStack(Items.NETHERITE_AXE), victim, "Fulano", 0L);
            startFirst(pool, 0L);

            when(server.overworld().getGameTime()).thenReturn(3L * 24000L);
            pool.tickAuctionClosing(server);

            assertEquals(2, pool.list().size(), "nothing is destroyed just for going unsold");
            assertTrue(pool.currentAuctionItem().isEmpty(), "closed, and nothing reopens on its own");
            List<AuctionPoolManager.PooledItem> items = pool.list();
            assertEquals(Items.NETHERITE_AXE, items.get(0).stack().getItem(), "never got its turn, still waits at the front");
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
            startFirst(pool, 0L);
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

            Optional<AuctionPoolManager.PooledItem> first = pool.retrieveNext();

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
            startFirst(pool, 0L);
            pool.placeBid(bidder, 100.0);

            Optional<AuctionPoolManager.PooledItem> withdrawn = pool.retrieveNext();

            assertTrue(withdrawn.isPresent());
            assertEquals(1000.0, economy.getBalance(bidder), "no money left stranded with neither the item nor a refund");
            assertTrue(pool.currentAuctionItem().isEmpty(), "the active auction is gone along with the item");
        });
    }

    @Test
    void withdrawWithNoActiveBidDoesNotTouchAnyBalance() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);

            assertTrue(pool.retrieveNext().isPresent());
        });
    }
}
