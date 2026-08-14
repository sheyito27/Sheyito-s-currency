package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/debt.json. The death penalty has two branches
 * depending on the player's balance at the moment they die: at or below {@link #balanceThreshold}
 * it's a flat {@link #penaltyAmount} (can push the balance negative, creating debt); above it,
 * it's {@link #penaltyPercent} of the current balance (never enough to go negative).
 */
public class DebtConfig {
    public boolean enabled = true;
    public double balanceThreshold = 500.0;
    public double penaltyAmount = 500.0;
    public double penaltyPercent = 0.30;
    public int deadlineGameDays = 1;
    public boolean broadcastOnDebtIncurred = true;

    public static DebtConfig defaults() {
        return new DebtConfig();
    }
}
