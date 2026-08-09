package com.sheyito.economicmaster.shop;

import com.sheyito.economicmaster.economy.EconomyManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Pure business logic, no event/listener concerns. Both {@link #buy} and {@link #sell} follow
 * "validate everything, then mutate" - every check that can fail runs first, against the real
 * current container/balance state, and only once every check has passed does any actual
 * withdraw/deposit/take/give happen. Since this all runs synchronously within a single server
 * tick handling one interaction, there is no window for another transaction to interleave and
 * invalidate an already-passed check.
 */
public final class ShopTransactionService {

    private ShopTransactionService() {
    }

    public enum Result {
        OK,
        SIN_STOCK,
        SALDO_INSUFICIENTE,
        INVENTARIO_LLENO,
        DUENO_SIN_SALDO,
        COFRE_NO_DISPONIBLE
    }

    /** Shop action SELL: the shop sells from its chest to {@code buyer}. */
    public static Result buy(ServerPlayer buyer, ShopSign shop, Level level) {
        Container container = ShopContainers.resolveContainer(level, shop.chestPos());
        if (container == null) {
            return Result.COFRE_NO_DISPONIBLE;
        }
        if (ShopContainers.countMatching(container, shop.item()) < shop.quantity()) {
            return Result.SIN_STOCK;
        }
        if (EconomyManager.get().getBalance(buyer.getUUID()) < shop.price()) {
            return Result.SALDO_INSUFICIENTE;
        }
        int free = ShopContainers.freeCapacityFor(buyer.getInventory(), shop.item());
        if (free < shop.quantity()) {
            return Result.INVENTARIO_LLENO;
        }

        ItemStack withdrawn = ShopContainers.withdraw(container, shop.item(), shop.quantity());
        if (!EconomyManager.get().take(buyer.getUUID(), shop.price())) {
            ShopContainers.deposit(container, withdrawn);
            return Result.SALDO_INSUFICIENTE;
        }
        EconomyManager.get().give(shop.ownerUuid(), shop.price());
        buyer.getInventory().add(withdrawn);
        return Result.OK;
    }

    /** Shop action BUY: the shop (its owner) buys from {@code seller}, paying into their balance. */
    public static Result sell(ServerPlayer seller, ShopSign shop, Level level) {
        Container container = ShopContainers.resolveContainer(level, shop.chestPos());
        if (container == null) {
            return Result.COFRE_NO_DISPONIBLE;
        }
        if (ShopContainers.countMatching(seller.getInventory(), shop.item()) < shop.quantity()) {
            return Result.SIN_STOCK;
        }
        if (EconomyManager.get().getBalance(shop.ownerUuid()) < shop.price()) {
            return Result.DUENO_SIN_SALDO;
        }
        if (ShopContainers.freeCapacityFor(container, shop.item()) < shop.quantity()) {
            return Result.INVENTARIO_LLENO;
        }

        ItemStack withdrawn = ShopContainers.withdraw(seller.getInventory(), shop.item(), shop.quantity());
        if (!EconomyManager.get().take(shop.ownerUuid(), shop.price())) {
            seller.getInventory().add(withdrawn);
            return Result.DUENO_SIN_SALDO;
        }
        EconomyManager.get().give(seller.getUUID(), shop.price());
        ShopContainers.deposit(container, withdrawn);
        return Result.OK;
    }
}
