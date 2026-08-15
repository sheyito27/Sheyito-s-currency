package com.sheyito.economicmaster.embargo;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * While a player is in an embargo grace period ({@link EmbargoManager#isInGracePeriod}), blocks
 * the ways they could hide their equipment before it gets seized: dropping items on the ground,
 * or opening any chest/ender chest. Selling in shops, missions, and salary keep working
 * unaffected - only these two actions are cancelled here. Same cancellation pattern as
 * {@code shop.ShopProtectionListener}.
 */
public class EmbargoBlockListener {

    @SubscribeEvent
    public void onItemToss(ItemTossEvent event) {
        EmbargoManager manager = EmbargoManager.get();
        if (manager == null || !manager.isInGracePeriod(event.getPlayer().getUUID())) {
            return;
        }
        event.setCanceled(true);
        // Canceling this event only stops the item from entering the world - it does NOT undo
        // its removal from the inventory (see ItemTossEvent's own javadoc) - so without this,
        // the player would simply lose the item instead of just failing to drop it.
        ItemStack tossed = event.getEntity().getItem();
        event.getPlayer().getInventory().placeItemBackInInventory(tossed.copy());
        event.getPlayer().sendSystemMessage(Component.literal(
                "§c[Sheyito's currency] §fNo puedes tirar objetos mientras estas en periodo de gracia por deuda."));
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        LevelAccessor levelAccessor = event.getLevel();
        if (!(levelAccessor instanceof Level level) || level.isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        var block = level.getBlockState(event.getPos()).getBlock();
        if (!(block instanceof ChestBlock) && !(block instanceof EnderChestBlock)) {
            return;
        }
        EmbargoManager manager = EmbargoManager.get();
        if (manager == null || !manager.isInGracePeriod(player.getUUID())) {
            return;
        }
        event.setCanceled(true);
        player.sendSystemMessage(Component.literal(
                "§c[Sheyito's currency] §fNo puedes abrir cofres mientras estas en periodo de gracia por deuda."));
    }
}
