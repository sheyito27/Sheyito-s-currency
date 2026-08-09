package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/shop.json.
 */
public class ShopConfig {
    public int pendingSignTimeoutTicks = 600;

    public static ShopConfig defaults() {
        return new ShopConfig();
    }
}
