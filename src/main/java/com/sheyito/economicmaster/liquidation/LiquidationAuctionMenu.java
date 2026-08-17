package com.sheyito.economicmaster.liquidation;

import com.sheyito.economicmaster.auction.AuctionPoolManager;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * "Clicar para pujar", not "escribir una cifra por chat" - the user explicitly wanted the auction
 * to feel dynamic, not a bare command. Same molds as {@code LiquidationVoteMenu}/{@code TradeMenu}: a
 * vanilla {@link MenuType#GENERIC_9x3} chest, no client registration needed. One fresh instance
 * per viewer, always reading straight from {@link AuctionPoolManager} (the source of truth), so a
 * stale display never causes a wrong bid - worst case a button's label is one bid behind until the
 * next click refreshes it, same "snapshot, not a live push" tradeoff {@code LiquidationVoteMenu}
 * already accepted.
 *
 * <p>Layout (27 top slots, same shape {@code LiquidationVoteMenu} uses): row 0 shows the item up for
 * auction, row 1 shows who's winning and for how much, row 2 is the action row - one button per
 * configured {@code LiquidationConfig.bidIncrements} entry plus a "puja tu saldo máximo" button.
 */
public class LiquidationAuctionMenu extends AbstractContainerMenu {

    private static final int ITEM_SLOT = 4;
    private static final int INFO_SLOT = 13;
    private static final int BUTTON_ROW_START = 18;
    private static final int MAX_INCREMENT_BUTTONS = 8;
    private static final int MAX_BID_SLOT = 26;
    private static final int TOP_SLOTS = 27;
    private static final int PLAYER_INVENTORY_START = TOP_SLOTS;
    private static final int PLAYER_INVENTORY_END = PLAYER_INVENTORY_START + 36;

    private final UUID viewerUuid;
    private final SimpleContainer display = new SimpleContainer(TOP_SLOTS);

    public LiquidationAuctionMenu(int containerId, Inventory playerInventory, UUID viewerUuid) {
        super(MenuType.GENERIC_9x3, containerId);
        this.viewerUuid = viewerUuid;

        refreshDisplay();
        for (int i = 0; i < TOP_SLOTS; i++) {
            int row = i / 9;
            int col = i % 9;
            addSlot(new MirrorSlot(display, i, 8 + col * 18, 18 + row * 18));
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

    private void refreshDisplay() {
        AuctionPoolManager pool = AuctionPoolManager.get();
        Optional<AuctionPoolManager.PooledItem> current = pool.currentAuctionItem();
        display.setItem(ITEM_SLOT, current.map(item -> item.stack().copy()).orElse(ItemStack.EMPTY));

        display.setItem(INFO_SLOT, infoItem(pool));

        List<Double> increments = ConfigManager.liquidation().bidIncrements;
        int incrementCount = Math.min(increments.size(), MAX_INCREMENT_BUTTONS);
        for (int i = 0; i < incrementCount; i++) {
            display.setItem(BUTTON_ROW_START + i, incrementButton(pool, increments.get(i)));
        }
        for (int i = incrementCount; i < MAX_INCREMENT_BUTTONS; i++) {
            display.setItem(BUTTON_ROW_START + i, fillerPane());
        }
        display.setItem(MAX_BID_SLOT, maxBidButton());

        for (int i = 0; i < TOP_SLOTS; i++) {
            if (i != ITEM_SLOT && i != INFO_SLOT && (i < BUTTON_ROW_START || i > MAX_BID_SLOT) && display.getItem(i).isEmpty()) {
                display.setItem(i, fillerPane());
            }
        }
    }

    private static ItemStack infoItem(AuctionPoolManager pool) {
        ItemStack info = new ItemStack(Items.PAPER);
        Optional<UUID> bidder = pool.currentHighestBidder();
        if (bidder.isEmpty()) {
            info.set(DataComponents.CUSTOM_NAME, Component.literal("§7Sin pujas todavía"));
        } else {
            String bidderName = EconomyManager.get().getName(bidder.get());
            info.set(DataComponents.CUSTOM_NAME, Component.literal(
                    "§ePuja actual: " + Money.format(pool.currentHighestBid()) + " (" + bidderName + ")"));
        }
        return info;
    }

    private static ItemStack incrementButton(AuctionPoolManager pool, double increment) {
        ItemStack button = new ItemStack(Items.GOLD_NUGGET);
        double resultingBid = pool.currentHighestBid() + increment;
        button.set(DataComponents.CUSTOM_NAME, Component.literal(
                "§a+" + Money.format(increment) + " §7(puja total: " + Money.format(resultingBid) + ")"));
        return button;
    }

    private ItemStack maxBidButton() {
        double balance = EconomyManager.get().getBalance(viewerUuid);
        ItemStack button = new ItemStack(Items.NETHERITE_INGOT);
        button.set(DataComponents.CUSTOM_NAME, Component.literal(
                "§6Puja tu saldo máximo (" + Money.format(balance) + ")"));
        return button;
    }

    private static ItemStack fillerPane() {
        ItemStack pane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        pane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        return pane;
    }

    /** Null unless {@code slotId} is one of the action-row buttons - the exact amount that button
     * bids, computed fresh (not from the possibly-stale {@link #display}) so a click always acts
     * on the real current highest bid. */
    private Double amountForSlot(int slotId, AuctionPoolManager pool) {
        List<Double> increments = ConfigManager.liquidation().bidIncrements;
        int incrementCount = Math.min(increments.size(), MAX_INCREMENT_BUTTONS);
        if (slotId >= BUTTON_ROW_START && slotId < BUTTON_ROW_START + incrementCount) {
            return pool.currentHighestBid() + increments.get(slotId - BUTTON_ROW_START);
        }
        if (slotId == MAX_BID_SLOT) {
            return EconomyManager.get().getBalance(viewerUuid);
        }
        return null;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            AuctionPoolManager pool = AuctionPoolManager.get();
            Double amount = amountForSlot(slotId, pool);
            if (amount != null) {
                AuctionPoolManager.BidResult result = pool.placeBid(serverPlayer.getUUID(), amount, serverPlayer.getServer());
                announceResult(serverPlayer, result);
                refreshDisplay();
                return;
            }
        }
        boolean isPlayerInventory = slotId >= PLAYER_INVENTORY_START && slotId < PLAYER_INVENTORY_END;
        boolean isOutsideClick = slotId == -999;
        if (isPlayerInventory || isOutsideClick) {
            super.clicked(slotId, button, clickType, player);
        }
    }

    /** SUCCESS gets no message here - {@code AuctionPoolManager#announceBid} already broadcast
     * the "message + button" to every player, this one included. Only the failure paths, which
     * nobody else needs to hear about, get a private reply. */
    private static void announceResult(ServerPlayer player, AuctionPoolManager.BidResult result) {
        switch (result) {
            case SUCCESS -> {
            }
            case NO_ACTIVE_AUCTION -> player.sendSystemMessage(Component.literal(
                    "§c[Sheyito's currency] §fYa no hay ninguna subasta activa."));
            case CANNOT_BID_ON_OWN_ITEM -> player.sendSystemMessage(Component.literal(
                    "§c[Sheyito's currency] §fNo puedes pujar por tu propio objeto incautado."));
            case TOO_LOW -> player.sendSystemMessage(Component.literal(
                    "§c[Sheyito's currency] §fEsa puja ya no supera la puja más alta actual."));
            case INSUFFICIENT_FUNDS -> player.sendSystemMessage(Component.literal(
                    "§c[Sheyito's currency] §fNo tienes saldo suficiente para esa puja."));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return AuctionPoolManager.get() != null && AuctionPoolManager.get().currentAuctionItem().isPresent();
    }

    /** Pure display: mirrors container contents but never accepts interaction - same as
     * {@code trade.TradeMenu}/{@code LiquidationVoteMenu}'s private nested class of the same purpose. */
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
