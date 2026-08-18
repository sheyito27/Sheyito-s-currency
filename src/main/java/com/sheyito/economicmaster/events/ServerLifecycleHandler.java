package com.sheyito.economicmaster.events;

import com.sheyito.economicmaster.auction.AuctionPoolManager;
import com.sheyito.economicmaster.chunk.ChunkClaimRegistry;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.dimension.DimensionUnlockManager;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.liquidation.LiquidationManager;
import com.sheyito.economicmaster.integration.FTBChunksCompat;
import com.sheyito.economicmaster.rent.RentManager;
import com.sheyito.economicmaster.salary.SalaryManager;
import com.sheyito.economicmaster.shop.ShopManager;
import com.sheyito.economicmaster.subscription.SubscriptionManager;
import com.sheyito.economicmaster.trade.TradeManager;
import net.minecraft.server.level.ServerPlayer;
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
        MonopolyManager.init(event.getServer());
        DimensionUnlockManager.init(event.getServer());
        ChunkClaimRegistry.init(event.getServer());
        LiquidationManager.init(event.getServer());
        AuctionPoolManager.init(event.getServer());
        RentManager.init(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        TradeManager.shutdown();
        ShopManager.shutdown();
        EconomyManager.shutdown();
        SalaryManager.shutdown();
        SubscriptionManager.shutdown();
        MonopolyManager.shutdown();
        DimensionUnlockManager.shutdown();
        ChunkClaimRegistry.shutdown();
        LiquidationManager.shutdown();
        AuctionPoolManager.shutdown();
        RentManager.shutdown();
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (EconomyManager.get() != null) {
            EconomyManager.get().trackName(event.getEntity().getUUID(), event.getEntity().getGameProfile().getName());
        }
        if (LiquidationManager.get() != null && event.getEntity() instanceof ServerPlayer player) {
            LiquidationManager.get().deliverPendingReturns(player);
        }
        if (AuctionPoolManager.get() != null && event.getEntity() instanceof ServerPlayer player) {
            AuctionPoolManager.get().deliverPending(player);
        }
        if (event.getEntity() instanceof ServerPlayer player) {
            FTBChunksCompat.applyPendingForceUnload(player);
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (TradeManager.get() != null) {
            TradeManager.get().handleDisconnect(event.getEntity().getUUID());
        }
    }
}
