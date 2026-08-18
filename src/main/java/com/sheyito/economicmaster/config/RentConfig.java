package com.sheyito.economicmaster.config;

import java.util.List;

/**
 * Backing schema for config/sheyitoscurrency/rent.json. Two unrelated charges share this file
 * because they share the same {@link #intervalGameDays} cadence: the progressive tax on profit
 * (see {@code rent.RentLogic}/{@code rent.RentManager}) and the per-force-loaded-chunk rent (see
 * {@code chunk.ChunkClaimRegistry#processForceLoadRent}).
 */
public class RentConfig {
    public boolean enabled = true;
    public int intervalGameDays = 7;

    /**
     * Flat-rate brackets on 7-day profit (not total net worth): the whole profit is taxed at the
     * percent of the highest bracket whose {@link Bracket#minProfit} it clears - not a marginal
     * calculation. Defaults: 1-10K -> 10%, 10K-100K -> 20%, 100K-1M -> 30%, 1M+ -> 40%.
     */
    public List<Bracket> profitBrackets = List.of(
            new Bracket(0, 0.10),
            new Bracket(10_000, 0.20),
            new Bracket(100_000, 0.30),
            new Bracket(1_000_000, 0.40));

    /** Base of the {@code forceLoadRentBase * n^1.5} force-load rent formula (n = currently
     * force-loaded chunks) - same shape as the chunk-claim price, base 10 instead of 1000. */
    public double forceLoadRentBase = 10.0;

    public static class Bracket {
        public double minProfit;
        public double percent;

        public Bracket() {
        }

        public Bracket(double minProfit, double percent) {
            this.minProfit = minProfit;
            this.percent = percent;
        }
    }

    public static RentConfig defaults() {
        return new RentConfig();
    }
}
