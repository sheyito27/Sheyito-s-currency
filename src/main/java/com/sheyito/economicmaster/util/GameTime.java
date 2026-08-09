package com.sheyito.economicmaster.util;

import net.minecraft.server.MinecraftServer;

/**
 * All "in-game day" scheduling in this mod (salary, subscriptions) is measured off the
 * overworld's raw tick counter rather than wall-clock time: it is unaffected by /time set
 * or sleeping through the night, and - crucially - it simply stops advancing whenever the
 * server is offline, so there is no "missed payments to catch up on" logic needed anywhere.
 */
public final class GameTime {

    private static final long TICKS_PER_DAY = 24000L;

    private GameTime() {
    }

    public static long currentDay(MinecraftServer server) {
        return server.overworld().getGameTime() / TICKS_PER_DAY;
    }
}
