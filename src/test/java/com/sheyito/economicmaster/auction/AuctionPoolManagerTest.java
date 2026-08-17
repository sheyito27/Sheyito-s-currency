package com.sheyito.economicmaster.auction;

import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.LiquidationConfig;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import net.minecraft.server.MinecraftServer;
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
 * "puesto de subastas" villager multiblock is for - see {@code liquidation.AuctionStandListener}, not
 * unit-testable here since it needs a real Level). Closing is a real-time no-bid countdown, same
 * per-second-resolution pattern as {@code LiquidationManagerTest}'s grace period ({@link
 * AuctionPoolManager#tickInactivity}, reset to 0 by every bid). See {@code embargoDeudas.md}'s "La
 * subasta con pujas" section for the full design. */
class AuctionPoolManagerTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private interface WithPool {
        void run(AuctionPoolManager pool, EconomyManager economy, MinecraftServer server) throws Exception;
    }

    private void withPool(int auctionInactivitySeconds, WithPool test) throws Exception {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;
        general.startingBalance = 0.0;
        LiquidationConfig liquidationConfig = new LiquidationConfig();
        liquidationConfig.enabled = true;
        liquidationConfig.auctionInactivitySeconds = auctionInactivitySeconds;
        liquidationConfig.bidIncrements = List.of(10.0, 100.0, 1000.0);

        EconomyManager economy = EconomyManager.createForTesting();
        EconomyManager.installForTesting(economy);
        AuctionPoolManager pool = AuctionPoolManager.createForTesting();
        AuctionPoolManager.installForTesting(pool);

        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);
        // Two anonymous online players by default - enough to clear the default
        // minPlayersToStartAuction=2 gate in every test that doesn't care about that gate itself
        // (their random UUIDs never collide with a test's victim/bidder UUIDs). Built as separate
        // statements, not inline inside thenReturn(...) - Mockito's stubbing is not reentrant, so
        // nesting mock()/when() calls (mockOnlinePlayer does both) inside another when(...)'s
        // argument list throws UnfinishedStubbingException.
        ServerPlayer defaultOnlinePlayerA = mockOnlinePlayer(UUID.randomUUID());
        ServerPlayer defaultOnlinePlayerB = mockOnlinePlayer(UUID.randomUUID());
        when(playerList.getPlayers()).thenReturn(List.of(defaultOnlinePlayerA, defaultOnlinePlayerB));

        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            mocked.when(ConfigManager::liquidation).thenReturn(liquidationConfig);
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
    private static void startFirst(AuctionPoolManager pool, MinecraftServer server) {
        AuctionPoolManager.PooledItem item = pool.list().get(0);
        assertEquals(AuctionPoolManager.StartResult.SUCCESS, pool.startAuction(item, server));
    }

    /** {@link AuctionPoolManager#tickInactivity} only does real work once every 20 calls (one
     * real second) - same throttle as {@code LiquidationManager#tickGrace}. */
    private static void tickSeconds(AuctionPoolManager pool, MinecraftServer server, int seconds) {
        for (int i = 0; i < seconds * 20; i++) {
            pool.tickInactivity(server);
        }
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

            AuctionPoolManager.StartResult result = pool.startAuction(axe, server);

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

            pool.startAuction(pickaxe, server);

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
            pool.startAuction(sword, server);

            AuctionPoolManager.StartResult result = pool.startAuction(axe, server);

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

            AuctionPoolManager.StartResult result = pool.startAuction(notPooled, server);

            assertEquals(AuctionPoolManager.StartResult.ITEM_NOT_FOUND, result);
        });
    }

    @Test
    void startAuctionFailsWithFewerThanTheConfiguredMinimumPlayersOnline() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            ServerPlayer onlyOnlinePlayer = mockOnlinePlayer(UUID.randomUUID());
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(onlyOnlinePlayer));
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            AuctionPoolManager.PooledItem item = pool.list().get(0);

            AuctionPoolManager.StartResult result = pool.startAuction(item, server);

            assertEquals(AuctionPoolManager.StartResult.NOT_ENOUGH_PLAYERS, result,
                    "only 1 of the default 2 required players is online");
            assertTrue(pool.currentAuctionItem().isEmpty(), "nothing started - the item stays parked in the pool");
        });
    }

    @Test
    void theVictimBeingOnlineDoesNotCountTowardsTheMinimumPlayers() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            ServerPlayer victimPlayer = mockOnlinePlayer(victim);
            ServerPlayer otherPlayer = mockOnlinePlayer(UUID.randomUUID());
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(victimPlayer, otherPlayer));
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            AuctionPoolManager.PooledItem item = pool.list().get(0);

            AuctionPoolManager.StartResult result = pool.startAuction(item, server);

            assertEquals(AuctionPoolManager.StartResult.NOT_ENOUGH_PLAYERS, result,
                    "2 players online, but one of them is the victim - only 1 counts");
        });
    }

    @Test
    void startAuctionSucceedsWithExactlyTheConfiguredMinimumPlayersOnline() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            ServerPlayer onlinePlayerA = mockOnlinePlayer(UUID.randomUUID());
            ServerPlayer onlinePlayerB = mockOnlinePlayer(UUID.randomUUID());
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(onlinePlayerA, onlinePlayerB));
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            AuctionPoolManager.PooledItem item = pool.list().get(0);

            AuctionPoolManager.StartResult result = pool.startAuction(item, server);

            assertEquals(AuctionPoolManager.StartResult.SUCCESS, result);
        });
    }

    @Test
    void validBidBecomesTheNewHighestAndTakesTheMoneyImmediately() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.give(bidder, 1000.0);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            startFirst(pool, server);

            AuctionPoolManager.BidResult result = pool.placeBid(bidder, 100.0, server);

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
            startFirst(pool, server);
            pool.placeBid(firstBidder, 100.0, server);

            AuctionPoolManager.BidResult result = pool.placeBid(secondBidder, 100.0, server);

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
            startFirst(pool, server);

            AuctionPoolManager.BidResult result = pool.placeBid(bidder, 100.0, server);

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
            startFirst(pool, server);

            AuctionPoolManager.BidResult result = pool.placeBid(victim, 100.0, server);

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
            startFirst(pool, server);
            pool.placeBid(firstBidder, 100.0, server);

            AuctionPoolManager.BidResult result = pool.placeBid(secondBidder, 200.0, server);

            assertEquals(AuctionPoolManager.BidResult.SUCCESS, result);
            assertEquals(1000.0, economy.getBalance(firstBidder), "outbid - refunded in full");
            assertEquals(800.0, economy.getBalance(secondBidder));
            assertEquals(secondBidder, pool.currentHighestBidder().orElseThrow());
            assertEquals(200.0, pool.currentHighestBid());
        });
    }

    @Test
    void tickInactivityDoesNothingBeforeTheTimeoutElapses() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.give(bidder, 1000.0);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            startFirst(pool, server);
            pool.placeBid(bidder, 100.0, server);

            tickSeconds(pool, server, 2);

            assertEquals(1, pool.list().size(), "still open - only 2 of 3 required seconds elapsed");
            assertEquals(100.0, pool.currentHighestBid());
        });
    }

    @Test
    void aBidResetsTheNoBidCountdownBackToZero() throws Exception {
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.give(bidder, 1000.0);
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            startFirst(pool, server);

            tickSeconds(pool, server, 2); // 2 of 3 seconds elapsed with no bids yet
            pool.placeBid(bidder, 100.0, server); // resets the countdown to 0
            tickSeconds(pool, server, 2); // if it had NOT reset, this would close it (2+2 >= 3)

            assertEquals(100.0, pool.currentHighestBid(), "still open - the bid reset the countdown");
            assertTrue(pool.currentAuctionItem().isPresent());
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
            startFirst(pool, server);
            pool.placeBid(bidder, 100.0, server);

            tickSeconds(pool, server, 3);

            assertTrue(pool.list().isEmpty(), "the sold item leaves the pool");
            verify(winnerPlayer.getInventory()).placeItemBackInInventory(
                    org.mockito.ArgumentMatchers.argThat(stack -> stack.getItem() == Items.DIAMOND_SWORD));
            assertEquals(900.0, economy.getBalance(bidder), "the escrowed 100 stays taken - never given to anyone, which is the burn");
        });
    }

    @Test
    void closingWithAWinnerAnnouncesTheRealItemInsteadOfAirX0() throws Exception {
        // Regression test: Inventory#placeItemBackInInventory mutates the ItemStack in place
        // (splits it down to empty) - the close message must be built BEFORE delivery runs, or it
        // reads the already-emptied stack and announces "Air x0" instead of the real item.
        withPool(3, (pool, economy, server) -> {
            UUID victim = UUID.randomUUID();
            UUID bidder = UUID.randomUUID();
            economy.give(bidder, 1000.0);
            ServerPlayer winnerPlayer = mockOnlinePlayer(bidder);
            when(server.getPlayerList().getPlayer(bidder)).thenReturn(winnerPlayer);
            ServerPlayer bystander = mockOnlinePlayer(UUID.randomUUID());
            ServerPlayer anotherBystander = mockOnlinePlayer(UUID.randomUUID());
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(bystander, anotherBystander));
            pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 0L);
            startFirst(pool, server);
            pool.placeBid(bidder, 100.0, server);

            tickSeconds(pool, server, 3);

            org.mockito.ArgumentCaptor<net.minecraft.network.chat.Component> captor =
                    org.mockito.ArgumentCaptor.forClass(net.minecraft.network.chat.Component.class);
            verify(bystander, org.mockito.Mockito.atLeastOnce()).sendSystemMessage(captor.capture());
            boolean announcedRealItem = captor.getAllValues().stream()
                    .map(net.minecraft.network.chat.Component::getString)
                    .anyMatch(text -> text.contains("Diamond Sword") && text.contains("x1"));
            boolean announcedAirX0 = captor.getAllValues().stream()
                    .map(net.minecraft.network.chat.Component::getString)
                    .anyMatch(text -> text.contains("Air x0"));
            assertTrue(announcedRealItem, "the close broadcast must name the real item and count");
            assertTrue(!announcedAirX0, "must never announce the already-emptied stack");
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
            startFirst(pool, server);
            pool.placeBid(bidder, 100.0, server);

            tickSeconds(pool, server, 3);

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
            startFirst(pool, server);

            tickSeconds(pool, server, 3);

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
            startFirst(pool, server);
            pool.placeBid(bidder, 100.0, server);

            tickSeconds(pool, server, 3);

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
            startFirst(pool, server);
            pool.placeBid(bidder, 100.0, server);

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
