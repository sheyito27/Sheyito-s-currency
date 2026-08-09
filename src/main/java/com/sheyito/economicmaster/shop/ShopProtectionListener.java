package com.sheyito.economicmaster.shop;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.UUID;

public class ShopProtectionListener {

    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof Level level) || level.isClientSide()) {
            return;
        }
        ResourceKey<Level> dimension = level.dimension();
        ShopManager manager = ShopManager.get();
        boolean isShopBlock = manager.isProtectedSign(dimension, event.getPos()) || manager.isProtectedChest(dimension, event.getPos());
        if (!isShopBlock) {
            return;
        }

        ServerPlayer player = event.getPlayer() instanceof ServerPlayer sp ? sp : null;
        UUID owner = manager.getShop(dimension, event.getPos())
                .map(ShopSign::ownerUuid)
                .or(() -> manager.chestOwner(dimension, event.getPos()))
                .orElse(null);

        boolean isOwner = player != null && player.getUUID().equals(owner);
        boolean isAdmin = player != null && player.hasPermissions(2);
        if (!isOwner && !isAdmin) {
            event.setCanceled(true);
            if (player != null) {
                player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fNo puedes romper esto, pertenece a otro jugador."));
            }
            return;
        }

        manager.getShop(dimension, event.getPos()).ifPresentOrElse(
                shop -> manager.removeShop(dimension, shop.signPos()),
                () -> manager.shopsForChest(dimension, event.getPos()).forEach(shop -> manager.removeShop(dimension, shop.signPos())));
    }
}
