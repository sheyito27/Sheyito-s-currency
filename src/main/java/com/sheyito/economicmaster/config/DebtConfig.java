package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/debt.json. When a player dies,
 * they lose {@link #penaltyPercent} of their current balance as a death penalty.
 * The penalty is always deducted via {@link com.sheyito.economicmaster.economy.EconomyManager#take(UUID, double)},
 * which ensures the balance never goes negative.
 */
public class DebtConfig {
    public boolean enabled = true;
    public double penaltyPercent = 0.50;

    public static DebtConfig defaults() {
        return new DebtConfig();
    }
}
