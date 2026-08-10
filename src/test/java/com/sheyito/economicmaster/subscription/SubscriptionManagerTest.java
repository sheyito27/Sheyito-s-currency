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

/**
 * Backs /subscribe <jugador> <dinero> <tiempo> [descripcion] | providers | clients | cancel
 * <numero>. Running "/subscribe X ..." registers that X (the payer) pays the command's runner
 * (the receiver) - so {@link SubscriptionManager#subscribe} takes a receiver {@link ServerPlayer}
 * and a payer {@link UUID}, in that order, and charges the payer, not the receiver.
 */
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
    void subscribeFailsWhenThePayerCantAfford() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID payer = UUID.randomUUID();
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            // payer has 0 balance

            assertFalse(subscriptions.subscribe(server, receiver, payer, 100.0, 5, ""));
            assertTrue(subscriptions.providersFor(payer).isEmpty());
        });
    }

    @Test
    void subscribeChargesFirstPeriodImmediately() throws Exception {
        withSubscriptions(5, 10, (subscriptions, economy, server) -> {
            UUID payerUuid = UUID.randomUUID();
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            economy.give(payerUuid, 100.0);

            assertTrue(subscriptions.subscribe(server, receiver, payerUuid, 50.0, 5, "renta"));
            assertEquals(50.0, economy.getBalance(payerUuid));
            assertEquals(50.0, economy.getBalance(receiver.getUUID()));

            List<PlayerSubscription> providers = subscriptions.providersFor(payerUuid);
            assertEquals(1, providers.size());
            PlayerSubscription sub = providers.get(0);
            assertEquals(receiver.getUUID().toString(), sub.sellerUuid);
            assertEquals(50.0, sub.price);
            assertEquals("renta", sub.description);
            assertEquals(15L, sub.nextChargeGameDay, "day 10 + 5 day interval");
        });
    }

    @Test
    void subscriptionIncomeCountsAsEarnedForTheReceiver() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID payerUuid = UUID.randomUUID();
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            economy.give(payerUuid, 100.0);

            subscriptions.subscribe(server, receiver, payerUuid, 100.0, 5, "");

            assertEquals(10.0, economy.getXp(receiver.getUUID()), "service income should be earned XP, not a free transfer");
        });
    }

    @Test
    void cancelByIndexRemovesThatSubscription() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID payerUuid = UUID.randomUUID();
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            economy.give(payerUuid, 10.0);
            subscriptions.subscribe(server, receiver, payerUuid, 10.0, 5, "");

            assertTrue(subscriptions.cancelByIndex(payerUuid, 1));
            assertTrue(subscriptions.providersFor(payerUuid).isEmpty());
        });
    }

    @Test
    void cancelByIndexOutOfRangeReturnsFalse() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> assertFalse(subscriptions.cancelByIndex(UUID.randomUUID(), 1)));
    }

    @Test
    void cancelByIndexOnlyRemovesThatOneEntry() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID payerUuid = UUID.randomUUID();
            economy.give(payerUuid, 20.0);
            subscriptions.subscribe(server, mockPlayer(UUID.randomUUID(), "ReceiverOne"), payerUuid, 10.0, 5, "primero");
            subscriptions.subscribe(server, mockPlayer(UUID.randomUUID(), "ReceiverTwo"), payerUuid, 10.0, 5, "segundo");

            assertTrue(subscriptions.cancelByIndex(payerUuid, 1));

            List<PlayerSubscription> remaining = subscriptions.providersFor(payerUuid);
            assertEquals(1, remaining.size());
            assertEquals("segundo", remaining.get(0).description);
        });
    }

    @Test
    void processDueChargesRenewsWhenFundsAreAvailable() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID payerUuid = UUID.randomUUID();
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            economy.give(payerUuid, 200.0);
            subscriptions.subscribe(server, receiver, payerUuid, 50.0, 5, "");

            // Advance time by mutating the same mocked server's overworld game time in place.
            when(server.overworld().getGameTime()).thenReturn(5L * 24000L);

            subscriptions.processDueCharges(server);

            assertEquals(100.0, economy.getBalance(payerUuid), "200 - 50 initial - 50 renewal");
            assertEquals(100.0, economy.getBalance(receiver.getUUID()));
            assertEquals(10L, subscriptions.providersFor(payerUuid).get(0).nextChargeGameDay);
        });
    }

    @Test
    void processDueChargesCancelsSubscriptionWithoutFunds() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            UUID payerUuid = UUID.randomUUID();
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            economy.give(payerUuid, 50.0);
            subscriptions.subscribe(server, receiver, payerUuid, 50.0, 5, "");
            // payer now has 0 balance, can't afford the renewal

            when(server.overworld().getGameTime()).thenReturn(5L * 24000L);
            subscriptions.processDueCharges(server);

            assertTrue(subscriptions.providersFor(payerUuid).isEmpty());
        });
    }

    @Test
    void clientsForOnlyReturnsSubscriptionsPayingThatReceiver() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            ServerPlayer otherReceiver = mockPlayer(UUID.randomUUID(), "OtherReceiver");

            UUID payerA = UUID.randomUUID();
            UUID payerB = UUID.randomUUID();
            UUID payerC = UUID.randomUUID();
            economy.give(payerA, 10.0);
            economy.give(payerB, 10.0);
            economy.give(payerC, 10.0);

            subscriptions.subscribe(server, receiver, payerA, 10.0, 5, "");
            subscriptions.subscribe(server, receiver, payerB, 10.0, 5, "");
            subscriptions.subscribe(server, otherReceiver, payerC, 10.0, 5, "");

            assertEquals(2, subscriptions.clientsFor(receiver.getUUID()).size());
        });
    }
}
