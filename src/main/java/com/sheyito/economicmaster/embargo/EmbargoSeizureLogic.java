package com.sheyito.economicmaster.embargo;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure logic for the "brutal" side of the embargo: deciding which items count as "armadura, arma
 * o herramienta" and pulling them out of a player. No manager/persistence dependency here, so it
 * can be tested with a real {@link Inventory} and a mocked owner.
 */
final class EmbargoSeizureLogic {

    private static final EquipmentSlot[] EQUIPMENT_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };

    /** Traditional main-inventory + hotbar range - deliberately NOT the full
     * {@code inventory.getContainerSize()}, since on some layouts that also exposes the
     * equipment slots already handled via {@link LivingEntity#getItemBySlot}/
     * {@code setItemSlot} below, which would double-count/clear the same physical slot twice. */
    private static final int MAIN_INVENTORY_SLOTS = 36;

    private EmbargoSeizureLogic() {
    }

    /**
     * One seized item plus where it physically came from - {@code originSlot} is null for
     * anything that was loose in the main inventory. Equipped and loose still get no special
     * treatment as auction candidates (confirmed with the user: both are equally up for grabs in
     * the vote) - this is purely so a candidate that comes back (didn't win the vote, or the whole
     * seizure gets returned some other way) can go back onto the body instead of just landing as
     * a loose stack in the backpack.
     */
    record SeizedItem(ItemStack stack, EquipmentSlot originSlot) {
    }

    /**
     * Removes every seizable item from {@code owner} (equipped armor/hands + loose main
     * inventory) and returns copies of what was taken, tagged with where each one came from.
     * Mutates {@code owner} directly - caller decides what happens to the result (vault it, vote
     * on it, etc).
     */
    static List<SeizedItem> collectSeizable(LivingEntity owner, Inventory inventory) {
        List<SeizedItem> seized = new ArrayList<>();

        for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
            ItemStack equipped = owner.getItemBySlot(slot);
            if (isSeizable(equipped)) {
                seized.add(new SeizedItem(equipped.copy(), slot));
                owner.setItemSlot(slot, ItemStack.EMPTY);
            }
        }

        int slots = Math.min(MAIN_INVENTORY_SLOTS, inventory.getContainerSize());
        for (int i = 0; i < slots; i++) {
            ItemStack stack = inventory.getItem(i);
            if (isSeizable(stack)) {
                seized.add(new SeizedItem(stack.copy(), null));
                inventory.setItem(i, ItemStack.EMPTY);
            }
        }

        return seized;
    }

    static boolean isSeizable(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item instanceof ArmorItem
                || item instanceof SwordItem
                || item instanceof AxeItem
                || item instanceof PickaxeItem
                || item instanceof ShovelItem
                || item instanceof HoeItem
                || item instanceof BowItem
                || item instanceof CrossbowItem
                || item instanceof TridentItem
                || item instanceof ShieldItem
                || item instanceof MaceItem;
    }
}
