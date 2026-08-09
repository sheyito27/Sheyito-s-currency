package com.sheyito.economicmaster.trade;

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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * Server-side-only menu class - the client never sees this class. Because {@link MenuType#GENERIC_9x5}
 * is a vanilla menu type, the client instantiates its own plain {@code ChestMenu} to mirror it and
 * renders it with the stock chest screen, so no client-side registration is needed anywhere.
 * Both players in a trade get their own {@code TradeMenu} instance, but the item-offer and
 * currency-deposit rows point at the *same* {@link SimpleContainer} objects (just swapped, "mine"
 * vs "theirs") held by the shared {@link TradeSession} - vanilla's per-tick {@code broadcastChanges()}
 * then keeps both clients in sync with zero custom networking.
 */
public class TradeMenu extends AbstractContainerMenu {

    private static final int OFFER_SIZE = 9;
    private static final int ROW_SIZE = 9;
    private static final int CURRENCY_SLOTS_START = 27;
    private static final int CURRENCY_SLOTS_END = CURRENCY_SLOTS_START + TradeSession.CURRENCY_DENOMINATIONS.size();
    private static final int CONFIRM_SLOT = 34;
    private static final int CANCEL_SLOT = 35;
    private static final int PLAYER_INVENTORY_START = 45;
    private static final int PLAYER_INVENTORY_END = 81;

    private final TradeSession session;
    private final UUID viewerUuid;

    public TradeMenu(int containerId, Inventory playerInventory, TradeSession session, UUID viewerUuid) {
        super(MenuType.GENERIC_9x5, containerId);
        this.session = session;
        this.viewerUuid = viewerUuid;

        UUID otherUuid = session.other(viewerUuid);
        SimpleContainer myOffer = session.offerContainerFor(viewerUuid);
        SimpleContainer theirOffer = session.offerContainerFor(otherUuid);
        SimpleContainer progress = session.progressContainer();
        SimpleContainer myCurrency = session.moneyItemsFor(viewerUuid);
        SimpleContainer theirCurrency = session.moneyItemsFor(otherUuid);
        SimpleContainer myMoneyTotal = session.moneyDisplayFor(viewerUuid);
        SimpleContainer theirMoneyTotal = session.moneyDisplayFor(otherUuid);
        SimpleContainer myConfirm = session.confirmDisplayFor(viewerUuid);
        SimpleContainer theirConfirm = session.confirmDisplayFor(otherUuid);

        // Row 0 (slots 0-8): items I'm offering - writable.
        for (int i = 0; i < ROW_SIZE; i++) {
            addSlot(new OwnedOfferSlot(myOffer, i, 8 + i * 18, 18, session));
        }
        // Row 1 (slots 9-17): items the other player is offering - read-only mirror.
        for (int i = 0; i < ROW_SIZE; i++) {
            addSlot(new MirrorSlot(theirOffer, i, 8 + i * 18, 36));
        }
        // Row 2 (slots 18-26): progress bar panels - read-only, shared instance so it's identical for both.
        for (int i = 0; i < ROW_SIZE; i++) {
            addSlot(new MirrorSlot(progress, i, 8 + i * 18, 54));
        }
        // Row 3 (slots 27-35): my currency deposit slots (green-tinted), my total, confirm, cancel.
        List<TradeSession.Denomination> denominations = TradeSession.CURRENCY_DENOMINATIONS;
        for (int i = 0; i < denominations.size(); i++) {
            addSlot(new CurrencySlot(myCurrency, i, 8 + i * 18, 72, session, denominations.get(i).item()));
        }
        addSlot(new MirrorSlot(fillerContainer(Items.LIME_STAINED_GLASS_PANE), 0, 8 + 5 * 18, 72));
        addSlot(new MirrorSlot(myMoneyTotal, 0, 8 + 6 * 18, 72));
        addSlot(new MirrorSlot(myConfirm, 0, 8 + 7 * 18, 72));
        addSlot(new MirrorSlot(cancelContainer(), 0, 8 + 8 * 18, 72));
        // Row 4 (slots 36-44): their currency mirror (read-only), their total, their confirm indicator.
        for (int i = 0; i < denominations.size(); i++) {
            addSlot(new MirrorSlot(theirCurrency, i, 8 + i * 18, 90));
        }
        addSlot(new MirrorSlot(fillerContainer(Items.GRAY_STAINED_GLASS_PANE), 0, 8 + 5 * 18, 90));
        addSlot(new MirrorSlot(theirMoneyTotal, 0, 8 + 6 * 18, 90));
        addSlot(new MirrorSlot(theirConfirm, 0, 8 + 7 * 18, 90));
        addSlot(new MirrorSlot(fillerContainer(Items.GRAY_STAINED_GLASS_PANE), 0, 8 + 8 * 18, 90));

        // Player's own inventory (3 rows) + hotbar, standard vanilla layout.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 126 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 184));
        }

        session.attachMenu(viewerUuid, this);
    }

    private static SimpleContainer fillerContainer(Item paneItem) {
        SimpleContainer container = new SimpleContainer(1);
        ItemStack pane = new ItemStack(paneItem);
        pane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        container.setItem(0, pane);
        return container;
    }

    private static SimpleContainer cancelContainer() {
        SimpleContainer container = new SimpleContainer(1);
        ItemStack barrier = new ItemStack(Items.BARRIER);
        barrier.set(DataComponents.CUSTOM_NAME, Component.literal("§cCancelar intercambio"));
        container.setItem(0, barrier);
        return container;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId == CONFIRM_SLOT) {
            session.toggleConfirm(viewerUuid);
            return;
        }
        if (slotId == CANCEL_SLOT) {
            if (player instanceof ServerPlayer serverPlayer) {
                TradeManager manager = TradeManager.get();
                if (manager != null) {
                    manager.cancel(viewerUuid, "cancelado por " + serverPlayer.getGameProfile().getName());
                }
            }
            return;
        }
        boolean isOwnOffer = slotId >= 0 && slotId < OFFER_SIZE;
        boolean isOwnCurrency = slotId >= CURRENCY_SLOTS_START && slotId < CURRENCY_SLOTS_END;
        boolean isPlayerInventory = slotId >= PLAYER_INVENTORY_START && slotId < PLAYER_INVENTORY_END;
        boolean isOutsideClick = slotId == -999;
        // Mirror rows, progress bar, money total displays and decorative filler: never interactive.
        if (isOwnOffer || isOwnCurrency || isPlayerInventory || isOutsideClick) {
            super.clicked(slotId, button, clickType, player);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack original = slot.getItem();
        ItemStack copy = original.copy();

        if (index < OFFER_SIZE || (index >= CURRENCY_SLOTS_START && index < CURRENCY_SLOTS_END)) {
            if (!moveItemStackTo(original, PLAYER_INVENTORY_START, PLAYER_INVENTORY_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= PLAYER_INVENTORY_START && index < PLAYER_INVENTORY_END) {
            if (session.isLocked()) {
                return ItemStack.EMPTY;
            }
            int currencySlot = currencySlotIndexFor(original.getItem());
            boolean movedToCurrency = currencySlot >= 0 && moveItemStackTo(original, currencySlot, currencySlot + 1, false);
            if (!movedToCurrency && !moveItemStackTo(original, 0, OFFER_SIZE, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY;
        }

        if (original.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    private static int currencySlotIndexFor(Item item) {
        List<TradeSession.Denomination> denominations = TradeSession.CURRENCY_DENOMINATIONS;
        for (int i = 0; i < denominations.size(); i++) {
            if (denominations.get(i).item() == item) {
                return CURRENCY_SLOTS_START + i;
            }
        }
        return -1;
    }

    @Override
    public boolean stillValid(Player player) {
        return !session.isFinished();
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer serverPlayer) {
            TradeManager manager = TradeManager.get();
            if (manager != null) {
                manager.handleMenuClosed(viewerUuid, serverPlayer.getServer());
            }
        }
    }

    /** Row 0: the viewer's own offer. Writable, but frozen once both sides have confirmed. */
    private static class OwnedOfferSlot extends Slot {
        private final TradeSession session;

        OwnedOfferSlot(Container container, int slot, int x, int y, TradeSession session) {
            super(container, slot, x, y);
            this.session = session;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !session.isLocked();
        }

        @Override
        public boolean mayPickup(Player player) {
            return !session.isLocked();
        }
    }

    /**
     * One of the viewer's own currency-deposit slots: only accepts its one designated
     * denomination item, frozen once both sides have confirmed. Depositing raises the money
     * offered, taking the item back out (normal slot interaction) lowers it - there is no
     * separate "withdraw" control needed.
     */
    private static class CurrencySlot extends Slot {
        private final TradeSession session;
        private final Item allowedItem;

        CurrencySlot(Container container, int slot, int x, int y, TradeSession session, Item allowedItem) {
            super(container, slot, x, y);
            this.session = session;
            this.allowedItem = allowedItem;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !session.isLocked() && stack.getItem() == allowedItem;
        }

        @Override
        public boolean mayPickup(Player player) {
            return !session.isLocked();
        }

        @Override
        public int getMaxStackSize() {
            return 64;
        }
    }

    /** Every other slot: pure display, mirrors container contents but never accepts interaction. */
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
