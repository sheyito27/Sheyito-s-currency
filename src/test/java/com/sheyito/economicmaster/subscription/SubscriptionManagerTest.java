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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Backs /subscribe <jugador> <dinero> [descripcion] | providers | clients | cancel <numero>. */
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
    void subscribeFailsWithoutEnoughFunds() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID seller = UUID.randomUUID();
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            // buyer has 0 balance

            assertFalse(subscriptions.subscribe(server, buyer, seller, 100.0, 5, ""));
            assertTrue(subscriptions.providersFor(buyer.getUUID()).isEmpty());
        });
    }

    @Test
    void subscribeChargesFirstPeriodImmediately() throws Exception {
        withSubscriptions(5, 10, (subscriptions, economy, server) -> {
            UUID sellerUuid = UUID.randomUUID();
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            economy.give(buyer.getUUID(), 100.0);

            assertTrue(subscriptions.subscribe(server, buyer, sellerUuid, 50.0, 5, "renta"));
            assertEquals(50.0, economy.getBalance(buyer.getUUID()));
            assertEquals(50.0, economy.getBalance(sellerUuid));

            List<PlayerSubscription> providers = subscriptions.providersFor(buyer.getUUID());
            assertEquals(1, providers.size());
            PlayerSubscription sub = providers.get(0);
            assertEquals(sellerUuid.toString(), sub.sellerUuid);
            assertEquals(50.0, sub.price);
            assertEquals("renta", sub.description);
            assertEquals(15L, sub.nextChargeGameDay, "day 10 + 5 day interval");
        });
    }

    @Test
    void subscriptionIncomeCountsAsEarnedForTheSeller() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID sellerUuid = UUID.randomUUID();
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            economy.give(buyer.getUUID(), 100.0);

            subscriptions.subscribe(server, buyer, sellerUuid, 100.0, 5, "");

            assertEquals(10.0, economy.getXp(sellerUuid), "service income should be earned XP, not a free transfer");
        });
    }

    @Test
    void cancelByIndexRemovesThatSubscription() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID sellerUuid = UUID.randomUUID();
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            economy.give(buyer.getUUID(), 10.0);
            subscriptions.subscribe(server, buyer, sellerUuid, 10.0, 5, "");

            assertTrue(subscriptions.cancelByIndex(buyer.getUUID(), 1));
            assertTrue(subscriptions.providersFor(buyer.getUUID()).isEmpty());
        });
    }

    @Test
    void cancelByIndexOutOfRangeReturnsFalse() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> assertFalse(subscriptions.cancelByIndex(UUID.randomUUID(), 1)));
    }

    @Test
    void cancelByIndexOnlyRemovesThatOneEntry() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            economy.give(buyer.getUUID(), 20.0);
            subscriptions.subscribe(server, buyer, UUID.randomUUID(), 10.0, 5, "primero");
            subscriptions.subscribe(server, buyer, UUID.randomUUID(), 10.0, 5, "segundo");

            assertTrue(subscriptions.cancelByIndex(buyer.getUUID(), 1));

            List<PlayerSubscription> remaining = subscriptions.providersFor(buyer.getUUID());
            assertEquals(1, remaining.size());
            assertEquals("segundo", remaining.get(0).description);
        });
    }

    @Test
    void processDueChargesRenewsWhenFundsAreAvailable() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID sellerUuid = UUID.randomUUID();
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            economy.give(buyer.getUUID(), 200.0);
            subscriptions.subscribe(server, buyer, sellerUuid, 50.0, 5, "");

            // Advance time by mutating the same mocked server's overworld game time in place.
            when(server.overworld().getGameTime()).thenReturn(5L * 24000L);

            subscriptions.processDueCharges(server);

            assertEquals(100.0, economy.getBalance(buyer.getUUID()), "200 - 50 initial - 50 renewal");
            assertEquals(100.0, economy.getBalance(sellerUuid));
            assertEquals(10L, subscriptions.providersFor(buyer.getUUID()).get(0).nextChargeGameDay);
        });
    }

    @Test
    void processDueChargesCancelsSubscriptionWithoutFunds() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID sellerUuid = UUID.randomUUID();
            ServerPlayer buyer = mockPlayer(UUID.randomUUID(), "Buyer");
            economy.give(buyer.getUUID(), 50.0);
            subscriptions.subscribe(server, buyer, sellerUuid, 50.0, 5, "");
            // buyer now has 0 balance, can't afford the renewal

            when(server.overworld().getGameTime()).thenReturn(5L * 24000L);
            subscriptions.processDueCharges(server);

            assertTrue(subscriptions.providersFor(buyer.getUUID()).isEmpty());
        });
    }

    @Test
    void clientsForOnlyReturnsSubscriptionsPayingThatSeller() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID sellerUuid = UUID.randomUUID();
            UUID otherSellerUuid = UUID.randomUUID();

            ServerPlayer buyerA = mockPlayer(UUID.randomUUID(), "A");
            ServerPlayer buyerB = mockPlayer(UUID.randomUUID(), "B");
            ServerPlayer buyerC = mockPlayer(UUID.randomUUID(), "C");
            economy.give(buyerA.getUUID(), 10.0);
            economy.give(buyerB.getUUID(), 10.0);
            economy.give(buyerC.getUUID(), 10.0);

            subscriptions.subscribe(server, buyerA, sellerUuid, 10.0, 5, "");
            subscriptions.subscribe(server, buyerB, sellerUuid, 10.0, 5, "");
            subscriptions.subscribe(server, buyerC, otherSellerUuid, 10.0, 5, "");

            assertEquals(2, subscriptions.clientsFor(sellerUuid).size());
        });
    }
}
