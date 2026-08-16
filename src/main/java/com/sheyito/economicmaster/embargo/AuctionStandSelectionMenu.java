package com.sheyito.economicmaster.embargo;

import com.sheyito.economicmaster.auction.AuctionPoolManager;
import com.sheyito.economicmaster.util.GameTime;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Opened by talking to a "puesto de subastas" villager ({@link AuctionStandListener}) - lists
 * every item currently sitting in {@link AuctionPoolManager}, click one to start the auction on
 * it right away ({@link AuctionPoolManager#startAuction}). Same chest-menu molds as
 * {@code EmbargoVoteMenu}/{@code LiquidationAuctionMenu} (vanilla {@link MenuType#GENERIC_9x3},
 * no client registration needed) - one click, no separate confirmation step, matching every other
 * menu in this mod (voting, bidding) already being immediate-on-click.
 */
public class AuctionStandSelectionMenu extends AbstractContainerMenu {

    private static final int CANDIDATE_SLOTS = 27;
    private static final int PLAYER_INVENTORY_START = CANDIDATE_SLOTS;
    private static final int PLAYER_INVENTORY_END = PLAYER_INVENTORY_START + 36;

    private final SimpleContainer candidates = new SimpleContainer(CANDIDATE_SLOTS);
    private final List<AuctionPoolManager.PooledItem> pooled;
    private final int candidateCount;

    public AuctionStandSelectionMenu(int containerId, Inventory playerInventory) {
        super(MenuType.GENERIC_9x3, containerId);

        pooled = AuctionPoolManager.get().list();
        candidateCount = Math.min(pooled.size(), CANDIDATE_SLOTS);
        for (int i = 0; i < candidateCount; i++) {
            candidates.setItem(i, pooled.get(i).stack().copy());
        }

        for (int i = 0; i < CANDIDATE_SLOTS; i++) {
            int row = i / 9;
            int col = i % 9;
            addSlot(new MirrorSlot(candidates, i, 8 + col * 18, 18 + row * 18));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 93 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 151));
        }
    }

    public static void open(ServerPlayer player) {
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new AuctionStandSelectionMenu(id, inv),
                Component.literal("Puesto de subastas")));
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId >= 0 && slotId < candidateCount && player instanceof ServerPlayer serverPlayer) {
            AuctionPoolManager.PooledItem chosen = pooled.get(slotId);
            AuctionPoolManager.StartResult result = AuctionPoolManager.get()
                    .startAuction(chosen, GameTime.currentDay(serverPlayer.getServer()));
            announceResult(serverPlayer, result);
            return;
        }
        boolean isPlayerInventory = slotId >= PLAYER_INVENTORY_START && slotId < PLAYER_INVENTORY_END;
        boolean isOutsideClick = slotId == -999;
        if (isPlayerInventory || isOutsideClick) {
            super.clicked(slotId, button, clickType, player);
        }
    }

    private static void announceResult(ServerPlayer player, AuctionPoolManager.StartResult result) {
        switch (result) {
            case SUCCESS -> player.sendSystemMessage(Component.literal(
                    "§a[Sheyito's currency] §f¡Subasta empezada! Usa /liquidation auction para pujar."));
            case ALREADY_ACTIVE -> player.sendSystemMessage(Component.literal(
                    "§c[Sheyito's currency] §fYa hay una subasta en curso - espera a que termine para empezar otra."));
            case ITEM_NOT_FOUND -> player.sendSystemMessage(Component.literal(
                    "§c[Sheyito's currency] §fEse objeto ya no está en la pool."));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return true;
    }

    /** Pure display: mirrors container contents but never accepts interaction - same as
     * {@code trade.TradeMenu}/{@code EmbargoVoteMenu}'s private nested class of the same purpose. */
    private static class MirrorSlot extends Slot {
        MirrorSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }
}
