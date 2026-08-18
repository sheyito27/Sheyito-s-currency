package com.sheyito.economicmaster.economy;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.config.SalaryConfig;
import com.sheyito.economicmaster.config.TransmissionTaxConfig;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

/**
 * Balance math backing /bal, /baltop, /pay, /eco give|take|set, /sc reward,
 * mob kills and salary payouts all live here - the commands themselves are thin dispatchers
 * around these methods.
 */
class EconomyManagerTest {

    private interface WithConfig {
        void run(EconomyManager economy) throws Exception;
    }

    /** Every balance mutation rounds via Money, which reads ConfigManager.general(), and
     * giveEarned() additionally reads ConfigManager.salary() - both are stubbed here so the
     * whole manager can be exercised without a running server. The transmission tax is
     * disabled by default so existing tests can keep asserting exact amounts; tests that care
     * about the tax use {@link #withTaxedEconomy} instead. */
    private void withEconomy(WithConfig test) throws Exception {
        TransmissionTaxConfig tax = new TransmissionTaxConfig();
        tax.enabled = false;
        withEconomy(tax, test);
    }

    /** Same as {@link #withEconomy(WithConfig)} but with the transmission tax enabled at
     * {@code taxPercent}, for tests that specifically exercise pay()'s doble-corte math. */
    private void withTaxedEconomy(double taxPercent, WithConfig test) throws Exception {
        TransmissionTaxConfig tax = new TransmissionTaxConfig();
        tax.enabled = true;
        tax.taxPercent = taxPercent;
        withEconomy(tax, test);
    }

    private void withEconomy(TransmissionTaxConfig tax, WithConfig test) throws Exception {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;
        general.startingBalance = 0.0;

        SalaryConfig salary = new SalaryConfig();
        salary.xpPerCoin = 0.1;
        salary.maxLevel = 20;
        salary.levelCurveBaseXp = 20.0;

        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            mocked.when(ConfigManager::salary).thenReturn(salary);
            mocked.when(ConfigManager::transmissionTax).thenReturn(tax);
            test.run(EconomyManager.createForTesting());
        }
    }

    @Test
    void newPlayerStartsAtConfiguredStartingBalance() throws Exception {
        withEconomy(economy -> assertEquals(0.0, economy.getBalance(UUID.randomUUID())));
    }

    @Test
    void giveIncreasesBalance() throws Exception {
        withEconomy(economy -> {
            UUID player = UUID.randomUUID();
            economy.give(player, 50.0);
            assertEquals(50.0, economy.getBalance(player));
        });
    }

    @Test
    void takeFailsWithoutEnoughFunds() throws Exception {
        withEconomy(economy -> {
            UUID player = UUID.randomUUID();
            economy.give(player, 10.0);
            assertFalse(economy.take(player, 20.0));
            assertEquals(10.0, economy.getBalance(player), "a failed take must not change the balance");
        });
    }

    @Test
    void takeSucceedsAndDeducts() throws Exception {
        withEconomy(economy -> {
            UUID player = UUID.randomUUID();
            economy.give(player, 30.0);
            assertTrue(economy.take(player, 10.0));
            assertEquals(20.0, economy.getBalance(player));
        });
    }

    @Test
    void setBalanceAllowsNegativeValues() throws Exception {
        withEconomy(economy -> {
            UUID player = UUID.randomUUID();
            economy.setBalance(player, -50.0);
            assertEquals(-50.0, economy.getBalance(player), "debt requires setBalance to not clamp at 0");
        });
    }

    @Test
    void chargeCanPushBalanceNegative() throws Exception {
        withEconomy(economy -> {
            UUID player = UUID.randomUUID();
            economy.give(player, 200.0);
            economy.charge(player, 500.0);
            assertEquals(-300.0, economy.getBalance(player));
        });
    }

    @Test
    void giveAfterChargeRepaysDebtWithoutClampingEarly() throws Exception {
        withEconomy(economy -> {
            UUID player = UUID.randomUUID();
            economy.charge(player, 500.0);
            economy.give(player, 200.0);
            assertEquals(-300.0, economy.getBalance(player), "partial repayment must not be forgiven by a floor at 0");

            economy.give(player, 300.0);
            assertEquals(0.0, economy.getBalance(player), "fully repaying the debt lands back at exactly 0");
        });
    }

    @Test
    void takeStillRefusesToGoNegative() throws Exception {
        withEconomy(economy -> {
            UUID player = UUID.randomUUID();
            economy.charge(player, 100.0);
            assertFalse(economy.take(player, 1.0), "take() must keep refusing spends while already in debt");
            assertEquals(-100.0, economy.getBalance(player));
        });
    }

    @Test
    void payMovesMoneyBetweenTwoAccounts() throws Exception {
        withEconomy(economy -> {
            UUID sender = UUID.randomUUID();
            UUID recipient = UUID.randomUUID();
            economy.give(sender, 100.0);

            assertTrue(economy.pay(sender, recipient, 40.0));
            assertEquals(60.0, economy.getBalance(sender));
            assertEquals(40.0, economy.getBalance(recipient));
        });
    }

    @Test
    void payFailsAndMovesNothingWithoutFunds() throws Exception {
        withEconomy(economy -> {
            UUID sender = UUID.randomUUID();
            UUID recipient = UUID.randomUUID();
            economy.give(sender, 5.0);

            assertFalse(economy.pay(sender, recipient, 40.0));
            assertEquals(5.0, economy.getBalance(sender));
            assertEquals(0.0, economy.getBalance(recipient));
        });
    }

    @Test
    void payRejectsZeroOrNegativeAmounts() throws Exception {
        withEconomy(economy -> {
            UUID sender = UUID.randomUUID();
            UUID recipient = UUID.randomUUID();
            economy.give(sender, 100.0);

            assertFalse(economy.pay(sender, recipient, 0.0));
            assertFalse(economy.pay(sender, recipient, -5.0));
            assertEquals(100.0, economy.getBalance(sender));
        });
    }

    @Test
    void payChargesGrossAndCreditsNetWhenTaxIsEnabled() throws Exception {
        withTaxedEconomy(0.10, (economy) -> {
            UUID sender = UUID.randomUUID();
            UUID recipient = UUID.randomUUID();
            economy.give(sender, 200.0);

            assertTrue(economy.pay(sender, recipient, 100.0));
            assertEquals(90.0, economy.getBalance(sender), "sender pays the 100 sticker price plus 10% tax on top (110), starting from 200");
            assertEquals(90.0, economy.getBalance(recipient), "recipient gets the 100 sticker price minus 10% tax (90)");
        });
    }

    @Test
    void payFailsWhenSenderCannotAffordTheGrossAmount() throws Exception {
        withTaxedEconomy(0.10, (economy) -> {
            UUID sender = UUID.randomUUID();
            UUID recipient = UUID.randomUUID();
            economy.give(sender, 105.0);

            assertFalse(economy.pay(sender, recipient, 100.0), "105 covers the 100 sticker price but not the 110 gross with tax");
            assertEquals(105.0, economy.getBalance(sender));
            assertEquals(0.0, economy.getBalance(recipient));
        });
    }

    @Test
    void grossWithTaxAndNetAfterTaxAreNoOpsWhenDisabled() throws Exception {
        withEconomy((economy) -> {
            assertEquals(100.0, economy.grossWithTax(100.0));
            assertEquals(100.0, economy.netAfterTax(100.0));
        });
    }

    @Test
    void giveEarnedGrantsBothBalanceAndXp() throws Exception {
        withEconomy(economy -> {
            UUID player = UUID.randomUUID();
            economy.giveEarned(player, 100.0);

            assertEquals(100.0, economy.getBalance(player));
            assertEquals(10.0, economy.getXp(player), "xpPerCoin=0.1 * 100 earned = 10 xp");
        });
    }

    @Test
    void plainGiveNeverGrantsXp() throws Exception {
        withEconomy(economy -> {
            UUID player = UUID.randomUUID();
            economy.give(player, 100.0);
            assertEquals(0.0, economy.getXp(player), "/eco give and /pay must not be a level-farming exploit");
        });
    }

    @Test
    void payDoesNotGrantXpToEitherSide() throws Exception {
        withEconomy(economy -> {
            UUID sender = UUID.randomUUID();
            UUID recipient = UUID.randomUUID();
            economy.give(sender, 100.0);
            economy.pay(sender, recipient, 100.0);

            assertEquals(0.0, economy.getXp(sender));
            assertEquals(0.0, economy.getXp(recipient));
        });
    }

    @Test
    void getLevelReflectsAccumulatedXp() throws Exception {
        withEconomy(economy -> {
            UUID player = UUID.randomUUID();
            assertEquals(0, economy.getLevel(player));

            // xpForLevel(1, 20) = 20 * fibonacci(1) = 20, needs 200 coins earned at 0.1 xp/coin
            economy.giveEarned(player, 200.0);
            assertEquals(1, economy.getLevel(player));
        });
    }

    @Test
    void topSortsByBalanceDescending() throws Exception {
        withEconomy(economy -> {
            UUID rich = UUID.randomUUID();
            UUID poor = UUID.randomUUID();
            UUID middle = UUID.randomUUID();
            economy.give(rich, 300.0);
            economy.give(poor, 10.0);
            economy.give(middle, 100.0);

            List<Map.Entry<UUID, Double>> top = economy.top(10);

            assertEquals(rich, top.get(0).getKey());
            assertEquals(middle, top.get(1).getKey());
            assertEquals(poor, top.get(2).getKey());
        });
    }

    @Test
    void topRespectsLimit() throws Exception {
        withEconomy(economy -> {
            for (int i = 0; i < 5; i++) {
                economy.give(UUID.randomUUID(), i);
            }
            assertEquals(2, economy.top(2).size());
        });
    }

    @Test
    void trackNameThenGetNameRoundTrips() throws Exception {
        withEconomy(economy -> {
            UUID player = UUID.randomUUID();
            economy.trackName(player, "Sheyito");
            assertEquals("Sheyito", economy.getName(player));
        });
    }

    @Test
    void getNameFallsBackToUuidWhenUnknown() throws Exception {
        withEconomy(economy -> {
            UUID player = UUID.randomUUID();
            assertEquals(player.toString(), economy.getName(player));
        });
    }
}
