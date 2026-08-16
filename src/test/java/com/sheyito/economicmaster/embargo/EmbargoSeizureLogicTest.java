package com.sheyito.economicmaster.embargo;

import com.sheyito.economicmaster.TestBootstrap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers what counts as "armadura, arma o herramienta" and the equipped+loose-inventory scan
 * behind the embargo's item seizure. */
class EmbargoSeizureLogicTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private static LivingEntity emptyEquipment() {
        LivingEntity owner = mock(LivingEntity.class);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            when(owner.getItemBySlot(slot)).thenReturn(ItemStack.EMPTY);
        }
        return owner;
    }

    private static Inventory inventoryOf(ItemStack... stacks) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getContainerSize()).thenReturn(stacks.length);
        for (int i = 0; i < stacks.length; i++) {
            when(inventory.getItem(i)).thenReturn(stacks[i]);
        }
        return inventory;
    }

    @Test
    void isSeizableAcceptsArmorWeaponsAndTools() {
        assertTrue(EmbargoSeizureLogic.isSeizable(new ItemStack(Items.DIAMOND_CHESTPLATE)));
        assertTrue(EmbargoSeizureLogic.isSeizable(new ItemStack(Items.NETHERITE_SWORD)));
        assertTrue(EmbargoSeizureLogic.isSeizable(new ItemStack(Items.IRON_PICKAXE)));
        assertTrue(EmbargoSeizureLogic.isSeizable(new ItemStack(Items.STONE_AXE)));
        assertTrue(EmbargoSeizureLogic.isSeizable(new ItemStack(Items.WOODEN_SHOVEL)));
        assertTrue(EmbargoSeizureLogic.isSeizable(new ItemStack(Items.DIAMOND_HOE)));
        assertTrue(EmbargoSeizureLogic.isSeizable(new ItemStack(Items.BOW)));
        assertTrue(EmbargoSeizureLogic.isSeizable(new ItemStack(Items.CROSSBOW)));
        assertTrue(EmbargoSeizureLogic.isSeizable(new ItemStack(Items.TRIDENT)));
        assertTrue(EmbargoSeizureLogic.isSeizable(new ItemStack(Items.SHIELD)));
        assertTrue(EmbargoSeizureLogic.isSeizable(new ItemStack(Items.MACE)));
    }

    @Test
    void isSeizableRejectsEverydayItemsAndEmptyStacks() {
        assertFalse(EmbargoSeizureLogic.isSeizable(new ItemStack(Items.DIRT)));
        assertFalse(EmbargoSeizureLogic.isSeizable(new ItemStack(Items.DIAMOND)));
        assertFalse(EmbargoSeizureLogic.isSeizable(ItemStack.EMPTY));
    }

    @Test
    void collectsEquippedArmorAndClearsTheSlot() {
        LivingEntity owner = emptyEquipment();
        when(owner.getItemBySlot(EquipmentSlot.HEAD)).thenReturn(new ItemStack(Items.DIAMOND_HELMET));
        Inventory inventory = inventoryOf();

        List<ItemStack> seized = EmbargoSeizureLogic.collectSeizable(owner, inventory);

        assertEquals(1, seized.size());
        assertEquals(Items.DIAMOND_HELMET, seized.get(0).getItem());
        verify(owner).setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
    }

    @Test
    void leavesEmptyHandsAndNonSeizableEquipmentAlone() {
        LivingEntity owner = emptyEquipment();
        Inventory inventory = inventoryOf();

        List<ItemStack> seized = EmbargoSeizureLogic.collectSeizable(owner, inventory);

        assertTrue(seized.isEmpty());
        verify(owner, never()).setItemSlot(any(), any());
    }

    @Test
    void collectsLooseWeaponsFromMainInventoryButLeavesEverythingElse() {
        LivingEntity owner = emptyEquipment();
        Inventory inventory = inventoryOf(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.DIRT, 64));

        List<ItemStack> seized = EmbargoSeizureLogic.collectSeizable(owner, inventory);

        assertEquals(1, seized.size());
        assertEquals(Items.IRON_SWORD, seized.get(0).getItem());
        verify(inventory).setItem(0, ItemStack.EMPTY);
        verify(inventory, never()).setItem(eq(1), any());
    }

    @Test
    void leavesNonSeizableItemsUntouched() {
        LivingEntity owner = emptyEquipment();
        Inventory inventory = inventoryOf(new ItemStack(Items.DIRT, 64), new ItemStack(Items.DIAMOND, 5));

        List<ItemStack> seized = EmbargoSeizureLogic.collectSeizable(owner, inventory);

        assertTrue(seized.isEmpty());
        verify(inventory, never()).setItem(anyInt(), any());
    }

    @Test
    void ignoresSlotsBeyondTheTraditional36MainInventorySlots() {
        // A 41-slot layout (some Inventory implementations also expose armor/offhand this way)
        // with an "extra" seizable item at index 40 - must stay untouched, since equipment is
        // already handled separately via getItemBySlot and double-counting the same physical
        // slot through both paths would be a bug.
        ItemStack[] stacks = new ItemStack[41];
        Arrays.fill(stacks, ItemStack.EMPTY);
        stacks[40] = new ItemStack(Items.NETHERITE_AXE);
        LivingEntity owner = emptyEquipment();
        Inventory inventory = inventoryOf(stacks);

        List<ItemStack> seized = EmbargoSeizureLogic.collectSeizable(owner, inventory);

        assertTrue(seized.isEmpty());
        verify(inventory, never()).setItem(eq(40), any());
    }

    @Test
    void equippedAndLooseSeizuresCombineIntoOneList() {
        LivingEntity owner = emptyEquipment();
        when(owner.getItemBySlot(EquipmentSlot.MAINHAND)).thenReturn(new ItemStack(Items.NETHERITE_SWORD));
        Inventory inventory = inventoryOf(new ItemStack(Items.IRON_PICKAXE));

        List<ItemStack> seized = EmbargoSeizureLogic.collectSeizable(owner, inventory);

        assertEquals(2, seized.size(), "equipped and loose items get no special treatment - both are seized the same way");
    }
}
