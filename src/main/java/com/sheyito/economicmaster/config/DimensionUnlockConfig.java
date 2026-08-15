package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/dimension_unlock.json. Traveling to any dimension
 * other than the overworld (the Nether, the End, or any modded dimension - detected generically,
 * nothing hardcoded per-dimension) costs {@link #price} Sheyicoins the first time. If the player
 * can't afford it, the travel is blocked and they stay in the overworld. Once paid, that
 * dimension is unlocked for that player forever - see {@code DimensionUnlockManager}.
 */
public class DimensionUnlockConfig {
    public boolean enabled = true;
    public double price = 5000.0;

    public static DimensionUnlockConfig defaults() {
        return new DimensionUnlockConfig();
    }
}
