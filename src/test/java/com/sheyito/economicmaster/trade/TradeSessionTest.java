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
 * (item/money duplication risk), so this is the deepest test coverage here. Money is not a
 * balance field: it is whatever currency items (copper/iron/gold/diamond/netherite) sit in
 * each player's dedicated deposit slots, so most money tests here work directly against
 * {@link TradeSession#moneyItemsFor}.
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

    /** Slot 2 of the currency container is gold ingot ($100 each) per CURRENCY_DENOMINATIONS. */
    private static final int GOLD_SLOT = 2;

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
    void moneyOfferedStartsAtZero() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> assertEquals(0L, session.moneyOffered(uuidA)));
    }

    @Test
    void depositingCurrencyItemsRaisesMoneyOffered() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            session.moneyItemsFor(uuidA).setItem(GOLD_SLOT, new ItemStack(Items.GOLD_INGOT, 2));
            assertEquals(200L, session.moneyOffered(uuidA), "2 gold ingots at $100 each");
        });
    }

    @Test
    void takingCurrencyItemsBackLowersMoneyOffered() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            session.moneyItemsFor(uuidA).setItem(GOLD_SLOT, new ItemStack(Items.GOLD_INGOT, 2));
            session.moneyItemsFor(uuidA).setItem(GOLD_SLOT, ItemStack.EMPTY);
            assertEquals(0L, session.moneyOffered(uuidA));
        });
    }

    @Test
    void moneyOfferedSumsAcrossDifferentDenominations() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            var deposits = session.moneyItemsFor(uuidA);
            deposits.setItem(0, new ItemStack(Items.COPPER_INGOT, 5)); // 5
            deposits.setItem(1, new ItemStack(Items.IRON_INGOT, 1)); // 10
            deposits.setItem(3, new ItemStack(Items.DIAMOND, 1)); // 1000
            assertEquals(1015L, session.moneyOffered(uuidA));
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
    void mutatingMoneyDepositAfterBothConfirmedResetsConfirmation() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            session.toggleConfirm(uuidA);
            session.toggleConfirm(uuidB);
            assertTrue(session.isLocked());

            session.moneyItemsFor(uuidA).setItem(GOLD_SLOT, new ItemStack(Items.GOLD_INGOT, 1));

            assertFalse(session.isLocked(), "changing a money deposit must force both sides to re-confirm, same as items");
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
            session.moneyItemsFor(uuidA).setItem(GOLD_SLOT, new ItemStack(Items.GOLD_INGOT, 3)); // $300

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
            assertEquals(300.0, economy.getBalance(uuidB), "the 3 deposited gold ingots convert into currency for B");
            assertEquals(0.0, economy.getBalance(uuidA), "A never had a balance to begin with - the money came from items, not a deduction");
            assertEquals(0L, session.moneyOffered(uuidA), "deposited currency items are consumed on completion");
            verify(inventoryB).placeItemBackInInventory(offeredByA);
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
    void abortReturnsDepositedCurrencyAsItemsNotMoney() throws Exception {
        withSession((session, economy, server, uuidA, uuidB) -> {
            ItemStack depositedGold = new ItemStack(Items.GOLD_INGOT, 2);
            session.moneyItemsFor(uuidA).setItem(GOLD_SLOT, depositedGold);

            ServerPlayer playerA = mock(ServerPlayer.class);
            Inventory inventoryA = mock(Inventory.class);
            when(playerA.getInventory()).thenReturn(inventoryA);
            when(server.getPlayerList().getPlayer(uuidA)).thenReturn(playerA);

            session.abort(server, "changed my mind");

            verify(inventoryA).placeItemBackInInventory(depositedGold);
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
