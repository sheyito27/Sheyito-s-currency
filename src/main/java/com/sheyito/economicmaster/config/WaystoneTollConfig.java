package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/waystone_toll.json. Using a Waystone (from the
 * optional Waystones mod) costs {@link #cost} Sheyicoins per teleport, deducted via
 * {@link com.sheyito.economicmaster.economy.EconomyManager#charge(java.util.UUID, double)} -
 * unlike most charges in this mod, this one does not check funds first, so it can leave the
 * balance negative. There is no separate "debt" state for that: a negative balance is just a
 * negative balance, visible via /bal.
 */
public class WaystoneTollConfig {
    public boolean enabled = true;
    public double cost = 100.0;

    public static WaystoneTollConfig defaults() {
        return new WaystoneTollConfig();
    }
}
