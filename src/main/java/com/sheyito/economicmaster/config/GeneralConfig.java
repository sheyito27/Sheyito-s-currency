package com.sheyito.economicmaster.config;

public class GeneralConfig {
    public String currencyName = "Sheyicoins";
    public int decimals = 2;
    public double startingBalance = 0.0;
    public boolean broadcastKillRewards = false;
    public boolean broadcastSalaryPayout = true;
    public boolean broadcastQuestRewards = true;

    public static GeneralConfig defaults() {
        return new GeneralConfig();
    }
}
