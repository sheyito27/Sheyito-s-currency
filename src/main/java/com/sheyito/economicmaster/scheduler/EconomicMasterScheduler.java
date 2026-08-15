package com.sheyito.economicmaster.scheduler;

import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.salary.SalaryManager;
import com.sheyito.economicmaster.shop.ShopManager;
import com.sheyito.economicmaster.subscription.SubscriptionManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Runs the real-time-based economy checks (salary payouts, subscription billing) every
 * 600 ticks (~30s at 20 TPS) instead of every tick, and batches JSON autosaves on the
 * same cadence so mob-farm-scale kill income does not turn into an IO storm.
 */
public class EconomicMasterScheduler {

    private static final int CHECK_INTERVAL_TICKS = 600;

    private int tickCounter = 0;

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (EconomyManager.get() == null) {
            return;
        }

        tickCounter++;
        if (tickCounter < CHECK_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;

        SalaryManager.get().tick(event.getServer());
        SubscriptionManager.get().processDueCharges(event.getServer());
        SubscriptionManager.get().expireInvites(event.getServer());

        EconomyManager.get().saveIfDirty();
        SalaryManager.get().saveIfDirty();
        SubscriptionManager.get().saveIfDirty();
        ShopManager.get().saveIfDirty();
    }
}
