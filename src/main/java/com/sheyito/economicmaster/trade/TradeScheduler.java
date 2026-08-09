package com.sheyito.economicmaster.trade;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Trades need per-tick resolution (the confirm progress bar fills over ~2-3s), unlike the
 * economy scheduler's coarse ~30s cadence, so this runs every tick but is a no-op instantly
 * whenever there are no active trades or invites.
 */
public class TradeScheduler {

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        TradeManager manager = TradeManager.get();
        if (manager != null) {
            manager.tickAll();
        }
    }
}
