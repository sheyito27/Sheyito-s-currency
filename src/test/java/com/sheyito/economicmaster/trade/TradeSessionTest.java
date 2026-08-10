package com.sheyito.economicmaster.trade;

import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.config.SalaryConfig;
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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Backs /trade, /trade accept and /trade cancel - and is the highest-stakes class in the mod
 * (item/money duplication risk), so this is the deepest test coverage here. Money is fixed at
 * invite time ({@link TradeSession#moneyPledged()}), always flowing from uuidA (the inviter) to
 * uuidB on completion - there is no way to change it from inside the session/GUI.
 */
class TradeSessionTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private interface WithSession {
        void run(TradeSession session, EconomyManager economy, MinecraftServer server, UUID uuidA, UUID uuidB) throws Exception;
    }

    private void withSession(WithSession test) throws Exception {
        withSession(0, "", test);
    }

    private void withSession(double money, String message, WithSession test) throws Exception {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;
        SalaryConfig salary = new SalaryConfig();
        salary.xpPerCoin = 0.1;
        salary.maxLevel = 20;
        salary.levelCurveBaseXp = 20.0;

        EconomyManager economy = EconomyManager.createForTesting();
        EconomyManager.installForTesting(economy);

        UUID uuidA = UUID.randomUUID();
        UUID uuidB = UUID.randomUUID();
        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        when(server.getPlayerList()).thenReturn(playerList);

        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            mocked.when(ConfigManager::salary).thenReturn(salary);
            test.run(new TradeSession(uuidA, uuidB, money, message), economy, server, uuidA, uuidB);
        } finally {
            EconomyManager.installForTesting(null);
        }
    }

    @Test
    void otherResolvesTheCounterpart() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            assertEquals(uuidB, session.other(uuidA));
            assertEquals(uuidA, session.other(uuidB));
        });
    }

    @Test
    void involvesOnlyRecognizesTheTwoParticipants() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            assertTrue(session.involves(uuidA));
            assertTrue(session.involves(uuidB));
            assertFalse(session.involves(UUID.randomUUID()));
        });
    }

    @Test
    void moneyPledgedDefaultsToZero() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> assertEquals(0.0, session.moneyPledged()));
    }

    @Test
    void moneyPledgedAndMessageAreFixedAtConstruction() throws Exception {
        withSession(150.0, "pago por diamantes", (session, economy, server, uuidA, uuidB) -> {
            assertEquals(150.0, session.moneyPledged());
            assertEquals("pago por diamantes", session.message());
        });
    }

    @Test
    void isLockedOnlyWhenBothSidesConfirm() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            assertFalse(session.isLocked());
            session.toggleConfirm(uuidA);
            assertFalse(session.isLocked());
            session.toggleConfirm(uuidB);
            assertTrue(session.isLocked());
        });
    }

    @Test
    void togglingConfirmOffWhileLockedUnlocksImmediately() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            session.toggleConfirm(uuidA);
            session.toggleConfirm(uuidB);
            assertTrue(session.isLocked());

            session.toggleConfirm(uuidA);
            assertFalse(session.isLocked(), "un-confirming must be able to abort the countdown");
        });
    }

    @Test
    void mutatingAnOfferAfterBothConfirmedResetsConfirmation() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            session.toggleConfirm(uuidA);
            session.toggleConfirm(uuidB);
            assertTrue(session.isLocked());

            session.offerContainerFor(uuidA).setItem(0, new ItemStack(Items.DIAMOND, 1));

            assertFalse(session.isLocked(), "changing an offer must force both sides to re-confirm");
        });
    }

    @Test
    void tickDoesNothingUntilBothConfirm() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            for (int i = 0; i < TradeSession.TOTAL_TICKS + 10; i++) {
                session.tick(server);
            }
            assertFalse(session.isFinished());
        });
    }

    @Test
    void tradeCompletesAtomicallyAfterTheConfirmBar() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            ItemStack offeredByA = new ItemStack(Items.DIAMOND, 5);
            session.offerContainerFor(uuidA).setItem(0, offeredByA);

            ServerPlayer playerA = mock(ServerPlayer.class);
            ServerPlayer playerB = mock(ServerPlayer.class);
            Inventory inventoryB = mock(Inventory.class);
            when(playerB.getInventory()).thenReturn(inventoryB);
            when(server.getPlayerList().getPlayer(uuidA)).thenReturn(playerA);
            when(server.getPlayerList().getPlayer(uuidB)).thenReturn(playerB);

            session.toggleConfirm(uuidA);
            session.toggleConfirm(uuidB);
            for (int i = 0; i < TradeSession.TOTAL_TICKS; i++) {
                session.tick(server);
            }

            assertTrue(session.isFinished());
            verify(inventoryB).placeItemBackInInventory(offeredByA);
        });
    }

    @Test
    void pledgedMoneyTransfersFromInviterToAccepterOnCompletion() throws Exception {
        withSession(300.0, "", (session, economy, server, uuidA, uuidB) -> {
            economy.give(uuidA, 300.0);

            ServerPlayer playerA = mock(ServerPlayer.class);
            ServerPlayer playerB = mock(ServerPlayer.class);
            when(playerB.getInventory()).thenReturn(mock(Inventory.class));
            when(server.getPlayerList().getPlayer(uuidA)).thenReturn(playerA);
            when(server.getPlayerList().getPlayer(uuidB)).thenReturn(playerB);

            session.toggleConfirm(uuidA);
            session.toggleConfirm(uuidB);
            for (int i = 0; i < TradeSession.TOTAL_TICKS; i++) {
                session.tick(server);
            }

            assertTrue(session.isFinished());
            assertEquals(0.0, economy.getBalance(uuidA));
            assertEquals(300.0, economy.getBalance(uuidB));
        });
    }

    @Test
    void tradeAbortsInsteadOfCompletingWhenInviterCanNoLongerAffordThePledge() throws Exception {
        withSession(300.0, "", (session, economy, server, uuidA, uuidB) -> {
            // uuidA never received the 300 they pledged - balance changed since the invite.
            ItemStack offeredByA = new ItemStack(Items.DIAMOND, 5);
            session.offerContainerFor(uuidA).setItem(0, offeredByA);

            ServerPlayer playerA = mock(ServerPlayer.class);
            Inventory inventoryA = mock(Inventory.class);
            when(playerA.getInventory()).thenReturn(inventoryA);
            ServerPlayer playerB = mock(ServerPlayer.class);
            when(server.getPlayerList().getPlayer(uuidA)).thenReturn(playerA);
            when(server.getPlayerList().getPlayer(uuidB)).thenReturn(playerB);

            session.toggleConfirm(uuidA);
            session.toggleConfirm(uuidB);
            for (int i = 0; i < TradeSession.TOTAL_TICKS; i++) {
                session.tick(server);
            }

            assertTrue(session.isFinished(), "an aborted trade still counts as finished");
            assertEquals(0.0, economy.getBalance(uuidB), "money must never transfer if the pledge can't be honored");
            verify(inventoryA).placeItemBackInInventory(offeredByA);
        });
    }

    @Test
    void abortReturnsOfferedItemsToTheirOriginalOwner() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            ItemStack offeredByA = new ItemStack(Items.EMERALD, 3);
            session.offerContainerFor(uuidA).setItem(0, offeredByA);

            ServerPlayer playerA = mock(ServerPlayer.class);
            Inventory inventoryA = mock(Inventory.class);
            when(playerA.getInventory()).thenReturn(inventoryA);
            when(server.getPlayerList().getPlayer(uuidA)).thenReturn(playerA);

            session.abort(server, "test cancel");

            verify(inventoryA).placeItemBackInInventory(offeredByA);
            assertTrue(session.isFinished());
        });
    }

    @Test
    void abortNeverTransfersThePledgedMoney() throws Exception {
        withSession(50.0, "", (session, economy, server, uuidA, uuidB) -> {
            economy.give(uuidA, 50.0);

            session.abort(server, "changed my mind");

            assertEquals(50.0, economy.getBalance(uuidA), "aborting must never take the pledge from the inviter");
            assertEquals(0.0, economy.getBalance(uuidB), "aborting must never grant currency to the other side");
        });
    }

    @Test
    void abortIsIdempotent() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            session.abort(server, "first");
            session.abort(server, "second");
            assertTrue(session.isFinished());
        });
    }
}
