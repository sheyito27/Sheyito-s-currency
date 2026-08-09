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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * Server-side-only menu class - the client never sees this class. Because {@link MenuType#GENERIC_9x4}
 * is a vanilla menu type, the client instantiates its own plain {@code ChestMenu} to mirror it and
 * renders it with the stock chest screen, so no client-side registration is needed anywhere.
 * Both players in a trade get their own {@code TradeMenu} instance, but rows 0/1 point at the
 * *same* {@link SimpleContainer} objects (just swapped, "mine" vs "theirs") held by the shared
 * {@link TradeSession} - vanilla's per-tick {@code broadcastChanges()} then keeps both clients in
 * sync with zero custom networking.
 */
public class TradeMenu extends AbstractContainerMenu {

    private static final int OFFER_SIZE = 9;
    private static final int ROW_SIZE = 9;
    private static final int CONFIRM_SLOT = 31;
    private static final int CANCEL_SLOT = 35;
    private static final int PLAYER_INVENTORY_START = 36;
    private static final int PLAYER_INVENTORY_END = 72;

    private final TradeSession session;
    private final UUID viewerUuid;

    public TradeMenu(int containerId, Inventory playerInventory, TradeSession session, UUID viewerUuid) {
        super(MenuType.GENERIC_9x4, containerId);
        this.session = session;
        this.viewerUuid = viewerUuid;

        UUID otherUuid = session.other(viewerUuid);
        SimpleContainer myOffer = session.offerContainerFor(viewerUuid);
        SimpleContainer theirOffer = session.offerContainerFor(otherUuid);
        SimpleContainer progress = session.progressContainer();
        SimpleContainer myMoney = session.moneyDisplayFor(viewerUuid);
        SimpleContainer theirMoney = session.moneyDisplayFor(otherUuid);
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
        // Row 3 (slots 27-35): money displays, confirm button, cancel button, decorative filler.
        addSlot(new MirrorSlot(myMoney, 0, 8, 72));
        addSlot(new MirrorSlot(theirMoney, 0, 26, 72));
        addSlot(new MirrorSlot(fillerContainer(), 0, 44, 72));
        addSlot(new MirrorSlot(fillerContainer(), 0, 62, 72));
        addSlot(new MirrorSlot(myConfirm, 0, 80, 72));
        addSlot(new MirrorSlot(fillerContainer(), 0, 98, 72));
        addSlot(new MirrorSlot(theirConfirm, 0, 116, 72));
        addSlot(new MirrorSlot(fillerContainer(), 0, 134, 72));
        addSlot(new MirrorSlot(cancelContainer(), 0, 152, 72));

        // Player's own inventory (3 rows) + hotbar, standard vanilla layout.
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 116 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 174));
        }

        session.attachMenu(viewerUuid, this);
    }

    private static SimpleContainer fillerContainer() {
        SimpleContainer container = new SimpleContainer(1);
        ItemStack pane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
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
        boolean isPlayerInventory = slotId >= PLAYER_INVENTORY_START && slotId < PLAYER_INVENTORY_END;
        boolean isOutsideClick = slotId == -999;
        // Mirror rows, progress bar, money displays and decorative filler: never interactive.
        if (isOwnOffer || isPlayerInventory || isOutsideClick) {
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

        if (index < OFFER_SIZE) {
            if (!moveItemStackTo(original, PLAYER_INVENTORY_START, PLAYER_INVENTORY_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= PLAYER_INVENTORY_START && index < PLAYER_INVENTORY_END) {
            if (session.isLocked()) {
                return ItemStack.EMPTY;
            }
            if (!moveItemStackTo(original, 0, OFFER_SIZE, false)) {
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
