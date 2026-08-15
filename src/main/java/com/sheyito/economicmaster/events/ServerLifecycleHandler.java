package com.sheyito.economicmaster.events;

import com.sheyito.economicmaster.chunk.ChunkClaimManager;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.dimension.DimensionUnlockManager;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.salary.SalaryManager;
import com.sheyito.economicmaster.shop.ShopManager;
import com.sheyito.economicmaster.subscription.SubscriptionManager;
import com.sheyito.economicmaster.trade.TradeManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

public class ServerLifecycleHandler {

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        ConfigManager.load();
        EconomyManager.init(event.getServer());
        SalaryManager.init(event.getServer());
        SubscriptionManager.init(event.getServer());
        TradeManager.init(event.getServer());
        ShopManager.init(event.getServer());
        DimensionUnlockManager.init(event.getServer());
        ChunkClaimManager.init(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        TradeManager.shutdown();
        ShopManager.shutdown();
        EconomyManager.shutdown();
        SalaryManager.shutdown();
        SubscriptionManager.shutdown();
        DimensionUnlockManager.shutdown();
        ChunkClaimManager.shutdown();
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (EconomyManager.get() != null) {
            EconomyManager.get().trackName(event.getEntity().getUUID(), event.getEntity().getGameProfile().getName());
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (TradeManager.get() != null) {
            TradeManager.get().handleDisconnect(event.getEntity().getUUID());
        }
    }
}
