package com.sheyito.economicmaster.auction;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The no-bid closing countdown needs the same per-tick resolution as the liquidation's own grace
 * period ({@code liquidation.LiquidationScheduler}), for the same reason: the coarse ~30s economy
 * scheduler (600 ticks) would only catch the timeout somewhere between 0 and 60s late - too loose
 * for a default of 30s. Runs every tick but is a no-op instantly whenever nothing is up for bid
 * ({@link AuctionPoolManager#tickInactivity}).
 */
public class AuctionScheduler {

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        AuctionPoolManager manager = AuctionPoolManager.get();
        if (manager != null) {
            manager.tickInactivity(event.getServer());
        }
    }
}
