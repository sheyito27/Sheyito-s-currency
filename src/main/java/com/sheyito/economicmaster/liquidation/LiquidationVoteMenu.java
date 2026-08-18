package com.sheyito.economicmaster.liquidation;

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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Server-side-only secret-ballot menu, one instance per viewer - same molds as
 * {@code trade.TradeMenu} (a vanilla {@link MenuType#GENERIC_9x3} chest, so no client
 * registration is needed). The two candidate rows are a read-only mirror built fresh from
 * {@link LiquidationManager#voteItems} - identical for everyone, since it's just a snapshot copy,
 * not a shared live container. The "tu voto" indicator slot is a plain field of THIS instance
 * only (nobody else's menu references it), which is exactly what makes the ballot private:
 * clicking a candidate calls {@link LiquidationManager#castVote}, and only this viewer's own
 * indicator (and thus only their own client, via vanilla's per-viewer {@code broadcastChanges()})
 * ever reflects it.
 */
public class LiquidationVoteMenu extends AbstractContainerMenu {

    private static final int CANDIDATE_ROWS = 2;
    private static final int CANDIDATE_SLOTS = CANDIDATE_ROWS * 9;
    private static final int PLAYER_INVENTORY_START = CANDIDATE_SLOTS + 9;
    private static final int PLAYER_INVENTORY_END = PLAYER_INVENTORY_START + 36;

    private final long voteId;
    private final UUID viewerUuid;
    private final SimpleContainer candidates = new SimpleContainer(CANDIDATE_SLOTS);
    private final SimpleContainer indicator = new SimpleContainer(1);
    private final int candidateCount;

    public LiquidationVoteMenu(int containerId, Inventory playerInventory, long voteId, UUID viewerUuid) {
        super(MenuType.GENERIC_9x3, containerId);
        this.voteId = voteId;
        this.viewerUuid = viewerUuid;

        List<ItemStack> items = LiquidationManager.get().voteItems(voteId);
        this.candidateCount = Math.min(items.size(), CANDIDATE_SLOTS);
        for (int i = 0; i < candidateCount; i++) {
            candidates.setItem(i, items.get(i).copy());
        }

        for (int i = 0; i < CANDIDATE_SLOTS; i++) {
            int row = i / 9;
            int col = i % 9;
            addSlot(new MirrorSlot(candidates, i, 8 + col * 18, 18 + row * 18));
        }

        refreshIndicator();
        addSlot(new MirrorSlot(indicator, 0, 8, 54));
        for (int col = 1; col < 9; col++) {
            addSlot(new MirrorSlot(fillerContainer(), 0, 8 + col * 18, 54));
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

    private void refreshIndicator() {
        Integer voted = LiquidationManager.get().voteOf(voteId, viewerUuid);
        if (voted == null || voted >= candidateCount) {
            ItemStack marker = new ItemStack(Items.GRAY_DYE);
            marker.set(DataComponents.CUSTOM_NAME, Component.literal("§7Aún no has votado"));
            indicator.setItem(0, marker);
            return;
        }
        ItemStack marker = candidates.getItem(voted).copy();
        marker.set(DataComponents.CUSTOM_NAME, Component.literal("§aTu voto"));
        indicator.setItem(0, marker);
    }

    private static SimpleContainer fillerContainer() {
        SimpleContainer container = new SimpleContainer(1);
        ItemStack pane = new ItemStack(Items.GRAY_STAINED_GLASS_PANE);
        pane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        container.setItem(0, pane);
        return container;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (slotId >= 0 && slotId < candidateCount && player instanceof ServerPlayer serverPlayer) {
            LiquidationManager.get().castVote(voteId, viewerUuid, slotId, serverPlayer.getServer());
            refreshIndicator();
            return;
        }
        boolean isPlayerInventory = slotId >= PLAYER_INVENTORY_START && slotId < PLAYER_INVENTORY_END;
        boolean isOutsideClick = slotId == -999;
        if (isPlayerInventory || isOutsideClick) {
            super.clicked(slotId, button, clickType, player);
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return LiquidationManager.get() != null && LiquidationManager.get().isVoteActive(voteId);
    }

    /** Pure display: mirrors container contents but never accepts interaction - same as
     * {@code trade.TradeMenu}'s private nested class of the same name/purpose. */
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
