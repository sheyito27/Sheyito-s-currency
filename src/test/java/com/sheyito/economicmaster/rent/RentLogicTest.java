package com.sheyito.economicmaster.rent;

import com.sheyito.economicmaster.config.RentConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Covers the flat-rate-by-bracket profit tax math (confirmed with the user: NOT marginal - the
 * whole profit is taxed at the percent of its own bracket, never split across brackets). */
class RentLogicTest {

    private static final List<RentConfig.Bracket> BRACKETS = List.of(
            new RentConfig.Bracket(0, 0.10),
            new RentConfig.Bracket(10_000, 0.20),
            new RentConfig.Bracket(100_000, 0.30),
            new RentConfig.Bracket(1_000_000, 0.40));

    @Test
    void firstBracketAppliesFromOneUpToJustBelowTenThousand() {
        assertEquals(0.10, RentLogic.bracketPercentFor(1, BRACKETS));
        assertEquals(0.10, RentLogic.bracketPercentFor(9_999, BRACKETS));
    }

    @Test
    void exactBracketBoundaryBelongsToTheHigherBracket() {
        assertEquals(0.20, RentLogic.bracketPercentFor(10_000, BRACKETS));
        assertEquals(0.30, RentLogic.bracketPercentFor(100_000, BRACKETS));
        assertEquals(0.40, RentLogic.bracketPercentFor(1_000_000, BRACKETS));
    }

    @Test
    void profitWellAboveTenMillionStaysCappedAtTheTopBracket() {
        assertEquals(0.40, RentLogic.bracketPercentFor(50_000_000, BRACKETS));
    }

    @Test
    void negativeProfitHasNoBracket() {
        // Never actually reached in RentManager's billing loop (it only calls taxFor when
        // profit > 0), but the pure function should still be defensible against it.
        assertEquals(0.0, RentLogic.bracketPercentFor(-500, BRACKETS));
    }

    @Test
    void zeroProfitFallsInTheFirstBracketButOwesNoTaxEitherWay() {
        // minProfit=0 puts exactly-zero profit in the first bracket - harmless, since
        // taxFor(0, ...) is 0 regardless of which bracket's percent gets multiplied by it.
        assertEquals(0.10, RentLogic.bracketPercentFor(0, BRACKETS));
        assertEquals(0.0, RentLogic.taxFor(0, BRACKETS));
    }

    @Test
    void taxIsFlatRateNotMarginal() {
        // 150,000 falls in the 100K bracket (30%) - the whole amount is taxed at 30%, not
        // split as 10K@10% + 90K@20% + 50K@30% (which would be 34,000, not 45,000).
        assertEquals(45_000.0, RentLogic.taxFor(150_000, BRACKETS), 0.001);
    }

    @Test
    void taxForFirstBracket() {
        assertEquals(500.0, RentLogic.taxFor(5_000, BRACKETS));
    }
}
