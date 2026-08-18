package com.sheyito.economicmaster.shop;

import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import com.sheyito.economicmaster.util.TransactionSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public class ShopTradeListener {

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        LevelAccessor levelAccessor = event.getLevel();
        if (!(levelAccessor instanceof Level level) || level.isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof SignBlockEntity sign)) {
            return;
        }

        ShopManager.get().getShop(level.dimension(), pos).ifPresent(shop -> {
            if (shop.ownerUuid().equals(player.getUUID())) {
                // The owner re-editing their own shop sign: let vanilla's sign editor open
                // normally instead of treating the click as a trade attempt.
                return;
            }
            event.setCanceled(true);
            if (!sign.isFacingFrontText(player)) {
                return;
            }
            handleTransaction(player, shop, level, sign);
        });
    }

    private void handleTransaction(ServerPlayer player, ShopSign shop, Level level, SignBlockEntity sign) {
        ShopTransactionService.Result result = shop.action() == ShopAction.SELL
                ? ShopTransactionService.buy(player, shop, level)
                : ShopTransactionService.sell(player, shop, level);

        switch (result) {
            case OK -> {
                boolean playerIsBuying = shop.action() == ShopAction.SELL;
                String verb = playerIsBuying ? "Compraste" : "Vendiste";
                double realAmount = playerIsBuying
                        ? EconomyManager.get().grossWithTax(shop.price())
                        : EconomyManager.get().netAfterTax(shop.price());
                player.sendSystemMessage(Component.literal("§a[Sheyito's currency] §f" + verb + " " + shop.quantity() + "x " + shop.item().getDescription().getString() + " por " + Money.format(realAmount) + " (IVA incluido)."));
                TransactionSounds.success(player);
                refreshStatusLine(sign, shop, level);
            }
            case SIN_STOCK -> {
                player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fEsta tienda no tiene stock suficiente."));
                TransactionSounds.failure(player);
            }
            case SALDO_INSUFICIENTE -> {
                player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fNo tienes saldo suficiente."));
                TransactionSounds.failure(player);
            }
            case INVENTARIO_LLENO -> {
                player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fNo tienes espacio suficiente en tu inventario."));
                TransactionSounds.failure(player);
            }
            case DUENO_SIN_SALDO -> {
                player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fEl dueño de esta tienda no tiene saldo suficiente para comprarte."));
                TransactionSounds.failure(player);
            }
            case COFRE_NO_DISPONIBLE -> {
                player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fEsta tienda ya no tiene un cofre valido."));
                TransactionSounds.failure(player);
            }
        }
    }

    private void refreshStatusLine(SignBlockEntity sign, ShopSign shop, Level level) {
        var container = ShopContainers.resolveContainer(level, shop.chestPos());
        String status = container == null ? "Sin cofre" : "Stock: " + ShopContainers.countMatching(container, shop.item());
        sign.setText(sign.getFrontText().setMessage(3, Component.literal(status)), true);
    }
}
