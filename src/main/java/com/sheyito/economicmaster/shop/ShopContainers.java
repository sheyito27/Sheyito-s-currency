package com.sheyito.economicmaster.shop;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.util.Optional;

/**
 * Pure helper functions over vanilla {@link Container}s - no state of its own. Reuses
 * {@link HopperBlockEntity#addItem} for depositing (the exact same vanilla code hoppers use,
 * already proven not to duplicate or lose items) instead of hand-rolling insertion logic.
 */
public final class ShopContainers {

    private ShopContainers() {
    }

    public static Optional<BlockPos> findAdjacentChest(Level level, BlockPos signPos) {
        BlockPos found = null;
        int count = 0;
        for (Direction direction : Direction.values()) {
            BlockPos candidate = signPos.relative(direction);
            BlockEntity be = level.getBlockEntity(candidate);
            if (be instanceof ChestBlockEntity) {
                found = candidate;
                count++;
            }
        }
        return count == 1 ? Optional.ofNullable(found) : Optional.empty();
    }

    public static Container resolveContainer(Level level, BlockPos chestPos) {
        BlockState state = level.getBlockState(chestPos);
        if (!(state.getBlock() instanceof ChestBlock chestBlock)) {
            return null;
        }
        return ChestBlock.getContainer(chestBlock, state, level, chestPos, false);
    }

    public static int countMatching(Container container, Item item) {
        ItemStack reference = new ItemStack(item);
        int total = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (ItemStack.isSameItemSameComponents(stack, reference)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** Simulated free capacity for more of {@code item}, counting empty slots and stackable partials. */
    public static int freeCapacityFor(Container container, Item item) {
        int maxStack = new ItemStack(item).getMaxStackSize();
        ItemStack reference = new ItemStack(item);
        int capacity = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty()) {
                capacity += maxStack;
            } else if (ItemStack.isSameItemSameComponents(stack, reference)) {
                capacity += Math.max(0, maxStack - stack.getCount());
            }
        }
        return capacity;
    }

    /** Caller must have already verified {@code countMatching(container, item) >= amount}. */
    public static ItemStack withdraw(Container container, Item item, int amount) {
        ItemStack reference = new ItemStack(item);
        int remaining = amount;
        for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, reference)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            container.removeItem(i, take);
            remaining -= take;
        }
        ItemStack result = new ItemStack(item, amount - remaining);
        container.setChanged();
        return result;
    }

    /** Caller must have already verified enough free capacity. Returns any leftover (should be empty). */
    public static ItemStack deposit(Container container, ItemStack stack) {
        ItemStack leftover = HopperBlockEntity.addItem(null, container, stack, null);
        container.setChanged();
        return leftover;
    }
}
