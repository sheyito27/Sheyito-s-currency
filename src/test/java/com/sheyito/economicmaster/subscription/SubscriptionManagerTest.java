package com.sheyito.economicmaster.subscription;

import com.mojang.authlib.GameProfile;
import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.config.SalaryConfig;
import com.sheyito.economicmaster.config.SubscriptionsConfig;
import com.sheyito.economicmaster.config.TransmissionTaxConfig;
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
 * Backs /subscribe <jugador> <dinero> <tiempo> [descripcion] | accept | deny | providers |
 * clients | cancel <numero>. Running "/subscribe X ..." only sends X (the payer) a proposal to
 * pay the command's runner (the receiver) - {@link SubscriptionManager#subscribe} (which takes a
 * receiver {@link ServerPlayer} and a payer {@link UUID}, in that order, and charges the payer)
 * is only ever reached through {@link SubscriptionManager#acceptInvite}.
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
        TransmissionTaxConfig tax = new TransmissionTaxConfig();
        tax.enabled = false;
        withSubscriptions(intervalGameDays, currentGameDay, tax, test);
    }

    /** Same as {@link #withSubscriptions(int, long, WithSubscriptions)} but with the
     * transmission tax enabled at {@code taxPercent}, for tests covering the doble-corte math
     * on both the initial charge ({@link SubscriptionManager#subscribe}) and renewals
     * ({@link SubscriptionManager#processDueCharges}). */
    private void withTaxedSubscriptions(int intervalGameDays, long currentGameDay, double taxPercent, WithSubscriptions test) throws Exception {
        TransmissionTaxConfig tax = new TransmissionTaxConfig();
        tax.enabled = true;
        tax.taxPercent = taxPercent;
        withSubscriptions(intervalGameDays, currentGameDay, tax, test);
    }

    private void withSubscriptions(int intervalGameDays, long currentGameDay, TransmissionTaxConfig tax, WithSubscriptions test) throws Exception {
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
            mocked.when(ConfigManager::transmissionTax).thenReturn(tax);
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
    void subscribeChargesGrossAndCreditsNetWhenTaxIsEnabled() throws Exception {
        withTaxedSubscriptions(5, 10, 0.10, (subscriptions, economy, server) -> {
            UUID payerUuid = UUID.randomUUID();
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            economy.give(payerUuid, 60.0);

            assertTrue(subscriptions.subscribe(server, receiver, payerUuid, 50.0, 5, "renta"));
            assertEquals(5.0, economy.getBalance(payerUuid), "payer pays the 50 sticker price plus 10% tax on top (55), starting from 60");
            assertEquals(45.0, economy.getBalance(receiver.getUUID()), "receiver gets the 50 sticker price minus 10% tax (45)");
            assertEquals(50.0, subscriptions.providersFor(payerUuid).get(0).price, "the stored agreed price stays untaxed - the tax is recomputed on every charge");
        });
    }

    @Test
    void processDueChargesAppliesTheTaxOnRenewalsToo() throws Exception {
        withTaxedSubscriptions(5, 0, 0.10, (subscriptions, economy, server) -> {
            UUID payerUuid = UUID.randomUUID();
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            economy.give(payerUuid, 110.0);
            subscriptions.subscribe(server, receiver, payerUuid, 50.0, 5, "");
            // first charge: payer 110 - 55 = 55, receiver 45

            when(server.overworld().getGameTime()).thenReturn(5L * 24000L);
            subscriptions.processDueCharges(server);

            assertEquals(0.0, economy.getBalance(payerUuid), "55 - 55 (renewal, gross)");
            assertEquals(90.0, economy.getBalance(receiver.getUUID()), "45 + 45 (renewal, net)");
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

    @Test
    void inviteDoesNotChargeUntilAccepted() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            ServerPlayer payer = mockPlayer(UUID.randomUUID(), "Payer");
            economy.give(payer.getUUID(), 100.0);
            when(server.getPlayerList().getPlayer(payer.getUUID())).thenReturn(payer);

            subscriptions.invite(server, receiver, payer.getUUID(), 50.0, 5, "renta");

            assertEquals(100.0, economy.getBalance(payer.getUUID()), "an invite alone must never charge anyone");
            assertTrue(subscriptions.providersFor(payer.getUUID()).isEmpty());
        });
    }

    @Test
    void acceptInviteChargesAndCreatesSubscription() throws Exception {
        withSubscriptions(5, 10, (subscriptions, economy, server) -> {
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            ServerPlayer payer = mockPlayer(UUID.randomUUID(), "Payer");
            economy.give(payer.getUUID(), 100.0);
            when(server.getPlayerList().getPlayer(payer.getUUID())).thenReturn(payer);
            when(server.getPlayerList().getPlayer(receiver.getUUID())).thenReturn(receiver);

            subscriptions.invite(server, receiver, payer.getUUID(), 50.0, 5, "renta");
            assertTrue(subscriptions.acceptInvite(server, payer));

            assertEquals(50.0, economy.getBalance(payer.getUUID()));
            assertEquals(50.0, economy.getBalance(receiver.getUUID()));
            List<PlayerSubscription> providers = subscriptions.providersFor(payer.getUUID());
            assertEquals(1, providers.size());
            assertEquals(15L, providers.get(0).nextChargeGameDay, "day 10 + 5 day interval");
        });
    }

    @Test
    void acceptInviteFailsWithNoPendingInvite() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) ->
                assertFalse(subscriptions.acceptInvite(server, mockPlayer(UUID.randomUUID(), "Payer"))));
    }

    @Test
    void acceptInviteLeavesTheInvitePendingWhenThePayerCantAfford() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            ServerPlayer payer = mockPlayer(UUID.randomUUID(), "Payer");
            when(server.getPlayerList().getPlayer(receiver.getUUID())).thenReturn(receiver);
            // payer has 0 balance

            subscriptions.invite(server, receiver, payer.getUUID(), 50.0, 5, "");
            assertFalse(subscriptions.acceptInvite(server, payer));

            economy.give(payer.getUUID(), 50.0);
            assertTrue(subscriptions.acceptInvite(server, payer), "a failed accept must not consume the invite - retrying after topping up should work");
        });
    }

    @Test
    void denyInviteRemovesItWithoutCharging() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            ServerPlayer payer = mockPlayer(UUID.randomUUID(), "Payer");
            economy.give(payer.getUUID(), 100.0);

            subscriptions.invite(server, receiver, payer.getUUID(), 50.0, 5, "");
            assertTrue(subscriptions.denyInvite(server, payer));

            assertEquals(100.0, economy.getBalance(payer.getUUID()));
            assertFalse(subscriptions.acceptInvite(server, payer), "a denied invite must no longer be acceptable");
        });
    }

    @Test
    void denyInviteOnNonExistentReturnsFalse() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) ->
                assertFalse(subscriptions.denyInvite(server, mockPlayer(UUID.randomUUID(), "Payer"))));
    }

    @Test
    void expireInvitesRemovesOldOnes() throws Exception {
        withSubscriptions(5, 0, (subscriptions, economy, server) -> {
            ServerPlayer receiver = mockPlayer(UUID.randomUUID(), "Receiver");
            ServerPlayer payer = mockPlayer(UUID.randomUUID(), "Payer");
            when(server.getTickCount()).thenReturn(0);

            subscriptions.invite(server, receiver, payer.getUUID(), 50.0, 5, "");

            when(server.getTickCount()).thenReturn(20 * 60 + 1);
            subscriptions.expireInvites(server);

            assertFalse(subscriptions.acceptInvite(server, payer), "an expired invite must no longer be acceptable");
        });
    }
}
