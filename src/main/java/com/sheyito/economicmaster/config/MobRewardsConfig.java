package com.sheyito.economicmaster.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Backing schema for config/sheyitoscurrency/mobs.json - a whitelist of entity
 * registry ids mapped to the money awarded when a player kills one.
 * Entities absent from {@link #rewards} grant nothing.
 */
public class MobRewardsConfig {
    public boolean enabled = false;
    public boolean requireDirectPlayerKill = true;
    public Map<String, Double> rewards = defaultRewards();

    private static Map<String, Double> defaultRewards() {
        Map<String, Double> map = new LinkedHashMap<>();
        map.put("minecraft:zombie", 5.0);
        map.put("minecraft:husk", 5.0);
        map.put("minecraft:drowned", 6.0);
        map.put("minecraft:skeleton", 5.0);
        map.put("minecraft:stray", 6.0);
        map.put("minecraft:spider", 4.0);
        map.put("minecraft:cave_spider", 6.0);
        map.put("minecraft:creeper", 8.0);
        map.put("minecraft:enderman", 10.0);
        map.put("minecraft:witch", 12.0);
        map.put("minecraft:phantom", 8.0);
        map.put("minecraft:slime", 3.0);
        map.put("minecraft:magma_cube", 4.0);
        map.put("minecraft:blaze", 15.0);
        map.put("minecraft:ghast", 20.0);
        map.put("minecraft:piglin", 6.0);
        map.put("minecraft:piglin_brute", 12.0);
        map.put("minecraft:hoglin", 10.0);
        map.put("minecraft:zoglin", 14.0);
        map.put("minecraft:pillager", 10.0);
        map.put("minecraft:vindicator", 14.0);
        map.put("minecraft:evoker", 25.0);
        map.put("minecraft:ravager", 40.0);
        map.put("minecraft:guardian", 15.0);
        map.put("minecraft:elder_guardian", 60.0);
        map.put("minecraft:shulker", 30.0);
        map.put("minecraft:wither_skeleton", 15.0);
        map.put("minecraft:wither", 250.0);
        map.put("minecraft:ender_dragon", 1000.0);
        return map;
    }

    public static MobRewardsConfig defaults() {
        return new MobRewardsConfig();
    }
}
