package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/salary.json. The salary paid each cycle scales
 * with the player's level (see {@link com.sheyito.economicmaster.util.LevelCurve}): it starts
 * at {@link #baseSalary} at level 0 and grows toward {@link #maxSalary} at {@link #maxLevel}.
 * Levels are earned via {@link #xpPerCoin} XP per coin of money the player earns (kills,
 * quest rewards, salary itself, subscription income) - the Fibonacci curve makes the top
 * levels extremely slow to reach on purpose.
 */
public class SalaryConfig {
    public boolean enabled = true;
    public int intervalGameDays = 1;
    public double baseSalary = 10.0;
    public double maxSalary = 500.0;
    public int maxLevel = 20;
    public double xpPerCoin = 0.1;
    public double levelCurveBaseXp = 20.0;

    public static SalaryConfig defaults() {
        return new SalaryConfig();
    }
}
