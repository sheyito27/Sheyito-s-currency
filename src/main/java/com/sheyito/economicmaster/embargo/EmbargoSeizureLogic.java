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
     * Removes every seizable item from {@code owner} (equipped armor/hands + loose main
     * inventory) and returns copies of what was taken. Mutates {@code owner} directly - caller
     * decides what happens to the result (vault it, vote on it, etc).
     */
    static List<ItemStack> collectSeizable(LivingEntity owner, Inventory inventory) {
        List<ItemStack> seized = new ArrayList<>();

        for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
            ItemStack equipped = owner.getItemBySlot(slot);
            if (isSeizable(equipped)) {
                seized.add(equipped.copy());
                owner.setItemSlot(slot, ItemStack.EMPTY);
            }
        }

        int slots = Math.min(MAIN_INVENTORY_SLOTS, inventory.getContainerSize());
        for (int i = 0; i < slots; i++) {
            ItemStack stack = inventory.getItem(i);
            if (isSeizable(stack)) {
                seized.add(stack.copy());
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
