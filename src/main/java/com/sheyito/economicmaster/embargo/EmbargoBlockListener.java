package com.sheyito.economicmaster.embargo;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * While a player is in an embargo grace period ({@link EmbargoManager#isInGracePeriod}), blocks
 * the ways they could hide their equipment before it gets seized: dropping items on the ground,
 * or opening any container. Selling in shops (sign-based, never opens a menu), missions, and
 * salary keep working unaffected. Same cancellation pattern as {@code shop.ShopProtectionListener}.
 *
 * <p>Two layers instead of one, on purpose: hardcoding a block-type whitelist (originally just
 * chest/ender chest) is a losing game against shulker boxes, barrels, and every modded storage
 * block that will ever exist - so {@link #onRightClickBlock} now checks generically whether the
 * block even offers a menu ({@link BlockState#getMenuProvider}) instead of naming specific
 * classes, catching any block-based container pre-emptively (no visual flash). That still can't
 * reach item-based inventories (a "mochila"/backpack item, a keybind-triggered GUI) or this mod's
 * own menus ({@code /trade}, whose shared window can just as easily hide gear from the seizure
 * scan) - {@link #onContainerOpen} is the universal safety net for those: it reacts one tick after
 * ANY {@link net.minecraft.world.inventory.AbstractContainerMenu} opens (confirmed via
 * {@code ServerPlayer#openMenu}, which every well-behaved menu - vanilla or modded - goes through)
 * and force-closes it immediately.
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
                "§c[Sheyito's currency] §fNo puedes tirar objetos mientras estás en período de gracia por deuda."));
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
        BlockState state = level.getBlockState(event.getPos());
        MenuProvider menuProvider = state.getMenuProvider(level, event.getPos());
        if (menuProvider == null) {
            return;
        }
        EmbargoManager manager = EmbargoManager.get();
        if (manager == null || !manager.isInGracePeriod(player.getUUID())) {
            return;
        }
        event.setCanceled(true);
        player.sendSystemMessage(Component.literal(
                "§c[Sheyito's currency] §fNo puedes abrir nada mientras tengas una deuda pendiente."));
    }

    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        EmbargoManager manager = EmbargoManager.get();
        if (manager == null || !manager.isInGracePeriod(player.getUUID())) {
            return;
        }
        player.closeContainer();
        player.sendSystemMessage(Component.literal(
                "§c[Sheyito's currency] §fNo puedes abrir nada mientras tengas una deuda pendiente."));
    }
}
