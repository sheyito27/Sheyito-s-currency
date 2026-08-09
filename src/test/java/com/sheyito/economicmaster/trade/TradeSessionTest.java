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
 * Backs /trade, /trade accept, /trade money and /trade cancel - and is the highest-stakes
 * class in the mod (item duplication risk), so this is the deepest test coverage here.
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
            test.run(new TradeSession(uuidA, uuidB), economy, server, uuidA, uuidB);
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
    void setMoneyOfferRejectsNegativeAmounts() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> assertFalse(session.setMoneyOffer(uuidA, -5.0)));
    }

    @Test
    void setMoneyOfferRejectsMoreThanCurrentBalance() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            economy.give(uuidA, 10.0);
            assertFalse(session.setMoneyOffer(uuidA, 20.0));
        });
    }

    @Test
    void setMoneyOfferSucceedsWithinBalance() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            economy.give(uuidA, 100.0);
            assertTrue(session.setMoneyOffer(uuidA, 40.0));
            assertEquals(40.0, session.moneyOffered(uuidA));
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
    void moneyOfferIsFrozenOnceBothSidesConfirm() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            economy.give(uuidA, 100.0);
            session.setMoneyOffer(uuidA, 5.0);
            session.toggleConfirm(uuidA);
            session.toggleConfirm(uuidB);

            boolean changed = session.setMoneyOffer(uuidA, 10.0);

            assertFalse(changed, "money offer must be frozen once locked, same as the item slots");
            assertEquals(5.0, session.moneyOffered(uuidA), "the rejected change must not have applied");
            assertTrue(session.isLocked(), "a rejected mutation must not affect the lock either");
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
            economy.give(uuidA, 100.0);
            ItemStack offeredByA = new ItemStack(Items.DIAMOND, 5);
            session.offerContainerFor(uuidA).setItem(0, offeredByA);
            session.setMoneyOffer(uuidA, 30.0);

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
            assertEquals(70.0, economy.getBalance(uuidA), "100 - 30 offered");
            assertEquals(30.0, economy.getBalance(uuidB));
            verify(inventoryB).placeItemBackInInventory(offeredByA);
        });
    }

    @Test
    void tradeAbortsInsteadOfCompletingIfFundsBecameInsufficient() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            economy.give(uuidA, 100.0);
            session.setMoneyOffer(uuidA, 100.0);

            ServerPlayer playerA = mock(ServerPlayer.class);
            Inventory inventoryA = mock(Inventory.class);
            when(playerA.getInventory()).thenReturn(inventoryA);
            when(server.getPlayerList().getPlayer(uuidA)).thenReturn(playerA);

            session.toggleConfirm(uuidA);
            session.toggleConfirm(uuidB);

            // Simulate the player spending money elsewhere (e.g. /pay) while the bar was filling.
            economy.take(uuidA, 60.0);

            for (int i = 0; i < TradeSession.TOTAL_TICKS; i++) {
                session.tick(server);
            }

            assertTrue(session.isFinished(), "an aborted trade is still a finished/closed session");
            assertEquals(40.0, economy.getBalance(uuidA), "no money should have moved on abort");
            assertEquals(0.0, economy.getBalance(uuidB));
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
    void abortIsIdempotent() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            session.abort(server, "first");
            session.abort(server, "second");
            assertTrue(session.isFinished());
        });
    }

    @Test
    void abortNeverMovesMoneySinceItIsOnlyChargedOnComplete() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            economy.give(uuidA, 100.0);
            session.setMoneyOffer(uuidA, 50.0);

            session.abort(server, "changed my mind");

            assertEquals(100.0, economy.getBalance(uuidA), "money is only ever deducted at complete(), never just for offering it");
        });
    }
}
