package com.sheyito.economicmaster.subscription;

import com.mojang.authlib.GameProfile;
import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.config.SalaryConfig;
import com.sheyito.economicmaster.config.SubscriptionsConfig;
import com.sheyito.economicmaster.data.PlayerSubscription;
import com.sheyito.economicmaster.economy.EconomyManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Backs /subscribe offer|<jugador>|cancel|stop|offers|status. */
class SubscriptionManagerTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private interface WithSubscriptions {
        void run(SubscriptionManager subscriptions, EconomyManager economy, MinecraftServer server) throws Exception;
    }

    private void withSubscriptions(int intervalGameDays, long currentGameDay, WithSubscriptions test) throws Exception {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;
        SalaryConfig salary = new SalaryConfig();
        salary.xpPerCoin = 0.1;
        salary.maxLevel = 20;
        salary.levelCurveBaseXp = 20.0;
        SubscriptionsConfig subs = new SubscriptionsConfig();
        subs.intervalGameDays = intervalGameDays;

        EconomyManager economy = EconomyManager.createForTesting();
        EconomyManager.installForTesting(economy);

        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        ServerLevel overworld = mock(ServerLevel.class);
        when(server.getPlayerList()).thenReturn(playerList);
        when(server.overworld()).thenReturn(overworld);
        when(overworld.getGameTime()).thenReturn(currentGameDay * 24000L);

        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            mocked.when(ConfigManager::salary).thenReturn(salary);
            mocked.when(ConfigManager::subscriptions).thenReturn(subs);
            test.run(SubscriptionManager.createForTesting(), economy, server);
        } finally {
            EconomyManager.installForTesting(null);
        }
    }

    private static ServerPlayer mockPlayer(UUID uuid, String name) {
        ServerPlayer player = mock(ServerPlayer.class);
        GameProfile profile = new GameProfile(uuid, name);
        when(player.getUUID()).thenReturn(uuid);
        when(player.getGameProfile()).thenReturn(profile);
        return player;
    }

    @Test
    void offerIsVisibleAfterSetOffer() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID seller = UUID.randomUUID();
            assertFalse(subscriptions.hasOffer(seller));

            subscriptions.setOffer(seller, 100.0);

            assertTrue(subscriptions.hasOffer(seller));
            assertEquals(100.0, subscriptions.getOfferPrice(seller));
        });
    }

    @Test
    void subscribeFailsWhenSellerHasNoOffer() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            assertFalse(subscriptions.subscribe(server, buyer, UUID.randomUUID()));
        });
    }

    @Test
    void subscribeFailsWithoutEnoughFunds() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID seller = UUID.randomUUID();
            subscriptions.setOffer(seller, 100.0);
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            // buyer has 0 balance

            assertFalse(subscriptions.subscribe(server, buyer, seller));
            assertNull(subscriptions.getSubscription(buyer.getUUID()));
        });
    }

    @Test
    void subscribeChargesFirstPeriodImmediately() throws Exception {
        withSubscriptions(5, 10, (subscriptions, economy, server) -> {
            UUID sellerUuid = UUID.randomUUID();
            subscriptions.setOffer(sellerUuid, 50.0);

            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            economy.give(buyer.getUUID(), 100.0);

            assertTrue(subscriptions.subscribe(server, buyer, sellerUuid));
            assertEquals(50.0, economy.getBalance(buyer.getUUID()));
            assertEquals(50.0, economy.getBalance(sellerUuid));

            PlayerSubscription sub = subscriptions.getSubscription(buyer.getUUID());
            assertEquals(sellerUuid.toString(), sub.sellerUuid);
            assertEquals(50.0, sub.price);
            assertEquals(15L, sub.nextChargeGameDay, "day 10 + 5 day interval");
        });
    }

    @Test
    void subscriptionIncomeCountsAsEarnedForTheSeller() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID sellerUuid = UUID.randomUUID();
            subscriptions.setOffer(sellerUuid, 100.0);
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            economy.give(buyer.getUUID(), 100.0);

            subscriptions.subscribe(server, buyer, sellerUuid);

            assertEquals(10.0, economy.getXp(sellerUuid), "service income should be earned XP, not a free transfer");
        });
    }

    @Test
    void cancelRemovesTheBuyersSubscription() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID sellerUuid = UUID.randomUUID();
            subscriptions.setOffer(sellerUuid, 10.0);
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            economy.give(buyer.getUUID(), 10.0);
            subscriptions.subscribe(server, buyer, sellerUuid);

            assertTrue(subscriptions.cancel(buyer.getUUID()));
            assertNull(subscriptions.getSubscription(buyer.getUUID()));
        });
    }

    @Test
    void cancelOnNonSubscriberReturnsFalse() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> assertFalse(subscriptions.cancel(UUID.randomUUID())));
    }

    @Test
    void processDueChargesRenewsWhenFundsAreAvailable() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID sellerUuid = UUID.randomUUID();
            subscriptions.setOffer(sellerUuid, 50.0);
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            economy.give(buyer.getUUID(), 200.0);
            subscriptions.subscribe(server, buyer, sellerUuid);

            // Advance time by mutating the same mocked server's overworld game time in place.
            when(server.overworld().getGameTime()).thenReturn(5L * 24000L);

            subscriptions.processDueCharges(server);

            assertEquals(100.0, economy.getBalance(buyer.getUUID()), "200 - 50 initial - 50 renewal");
            assertEquals(100.0, economy.getBalance(sellerUuid));
            assertEquals(10L, subscriptions.getSubscription(buyer.getUUID()).nextChargeGameDay);
        });
    }

    @Test
    void processDueChargesCancelsSubscriptionWithoutFunds() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID sellerUuid = UUID.randomUUID();
            subscriptions.setOffer(sellerUuid, 50.0);
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            economy.give(buyer.getUUID(), 50.0);
            subscriptions.subscribe(server, buyer, sellerUuid);
            // buyer now has 0 balance, can't afford the renewal

            when(server.overworld().getGameTime()).thenReturn(5L * 24000L);
            subscriptions.processDueCharges(server);

            assertNull(subscriptions.getSubscription(buyer.getUUID()));
        });
    }

    @Test
    void processDueChargesCancelsWhenSellerStoppedOffering() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID sellerUuid = UUID.randomUUID();
            subscriptions.setOffer(sellerUuid, 50.0);
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            economy.give(buyer.getUUID(), 200.0);
            subscriptions.subscribe(server, buyer, sellerUuid);

            subscriptions.removeOffer(server, sellerUuid);

            assertNull(subscriptions.getSubscription(buyer.getUUID()), "removeOffer must cascade-cancel active subscribers");
        });
    }

    @Test
    void subscriberCountOnlyCountsActiveSubscriptionsToThatSeller() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID sellerUuid = UUID.randomUUID();
            UUID otherSellerUuid = UUID.randomUUID();
            subscriptions.setOffer(sellerUuid, 10.0);
            subscriptions.setOffer(otherSellerUuid, 10.0);

            ServerPlayer buyerA = mockPlayer(UUID.randomUUID(), "A");
            ServerPlayer buyerB = mockPlayer(UUID.randomUUID(), "B");
            ServerPlayer buyerC = mockPlayer(UUID.randomUUID(), "C");
            economy.give(buyerA.getUUID(), 10.0);
            economy.give(buyerB.getUUID(), 10.0);
            economy.give(buyerC.getUUID(), 10.0);

            subscriptions.subscribe(server, buyerA, sellerUuid);
            subscriptions.subscribe(server, buyerB, sellerUuid);
            subscriptions.subscribe(server, buyerC, otherSellerUuid);

            assertEquals(2, subscriptions.subscriberCount(sellerUuid));
        });
    }

    @Test
    void getOffersReturnsAllActiveOffers() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID sellerA = UUID.randomUUID();
            UUID sellerB = UUID.randomUUID();
            subscriptions.setOffer(sellerA, 10.0);
            subscriptions.setOffer(sellerB, 20.0);

            Map<UUID, Double> offers = subscriptions.getOffers();
            assertEquals(2, offers.size());
            assertEquals(10.0, offers.get(sellerA));
            assertEquals(20.0, offers.get(sellerB));
        });
    }
}
