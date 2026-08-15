package com.sheyito.economicmaster.embargo;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The 30-second grace period needs per-tick resolution, unlike the economy scheduler's coarse
 * ~30s cadence (which would only catch expiry somewhere between 0 and 60s late) - same reasoning
 * as {@code trade.TradeScheduler}. Runs every tick but is a no-op instantly whenever nobody is
 * in a grace period ({@link EmbargoManager#tickGrace}).
 */
public class EmbargoScheduler {

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        EmbargoManager manager = EmbargoManager.get();
        if (manager != null) {
            manager.tickGrace(event.getServer());
        }
    }
}
