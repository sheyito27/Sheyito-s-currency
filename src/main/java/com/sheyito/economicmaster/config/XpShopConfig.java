package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/xp_shop.json, consumed by "/buy xp &lt;cantidad&gt;".
 * This is vanilla Minecraft experience (the enchanting XP bar), completely unrelated to the
 * mod's own salary level/XP system ({@link SalaryConfig#xpPerCoin}, {@link com.sheyito.economicmaster.util.LevelCurve}) -
 * buying XP here never touches a player's salary level.
 */
public class XpShopConfig {
    public double coinsPerXpPoint = 1.0;

    public static XpShopConfig defaults() {
        return new XpShopConfig();
    }
}
