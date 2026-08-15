package com.sheyito.economicmaster.rent;

import com.sheyito.economicmaster.config.RentConfig;

import java.util.List;

/**
 * Pure bracket math for the progressive profit tax - no manager/persistence dependency, so it's
 * testable with plain bracket lists. Flat-rate by bracket, not marginal: the whole 7-day profit
 * is taxed at the percent of the highest bracket it clears, confirmed with the user against the
 * alternative (per-bracket marginal calculation like real income tax) - simpler, and consistent
 * with how every other charge in this mod works (no marginal math anywhere else either).
 */
final class RentLogic {

    private RentLogic() {
    }

    /** The percent of the highest bracket whose {@code minProfit <= profit}. Brackets are
     * expected sorted ascending by {@code minProfit} (the config's declared order); 0 if the
     * list is empty or profit is negative. */
    static double bracketPercentFor(double profit, List<RentConfig.Bracket> brackets) {
        double percent = 0.0;
        for (RentConfig.Bracket bracket : brackets) {
            if (profit >= bracket.minProfit) {
                percent = bracket.percent;
            }
        }
        return percent;
    }

    /** Tax owed on {@code profit} (never negative - callers must pass {@code max(0, delta)}). */
    static double taxFor(double profit, List<RentConfig.Bracket> brackets) {
        return profit * bracketPercentFor(profit, brackets);
    }
}
