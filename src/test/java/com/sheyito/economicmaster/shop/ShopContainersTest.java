package com.sheyito.economicmaster.shop;

import com.sheyito.economicmaster.TestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Backs the stock/capacity checks a chest shop transaction validates before moving anything. */
class ShopContainersTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    @Test
    void countMatchingSumsAcrossMultipleStacks() {
        SimpleContainer container = new SimpleContainer(9);
        container.setItem(0, new ItemStack(Items.DIAMOND, 32));
        container.setItem(1, new ItemStack(Items.DIAMOND, 10));
        container.setItem(2, new ItemStack(Items.EMERALD, 5));

        assertEquals(42, ShopContainers.countMatching(container, Items.DIAMOND));
    }

    @Test
    void countMatchingIsZeroWhenAbsent() {
        SimpleContainer container = new SimpleContainer(9);
        assertEquals(0, ShopContainers.countMatching(container, Items.DIAMOND));
    }

    @Test
    void freeCapacityCountsEmptySlotsAtMaxStack() {
        SimpleContainer container = new SimpleContainer(2);
        assertEquals(128, ShopContainers.freeCapacityFor(container, Items.DIAMOND));
    }

    @Test
    void freeCapacityCountsPartialStacksToo() {
        SimpleContainer container = new SimpleContainer(1);
        container.setItem(0, new ItemStack(Items.DIAMOND, 60));
        assertEquals(4, ShopContainers.freeCapacityFor(container, Items.DIAMOND));
    }

    @Test
    void freeCapacityIgnoresSlotsWithADifferentItem() {
        SimpleContainer container = new SimpleContainer(1);
        container.setItem(0, new ItemStack(Items.EMERALD, 60));
        assertEquals(0, ShopContainers.freeCapacityFor(container, Items.DIAMOND));
    }

    @Test
    void withdrawTakesExactlyTheRequestedAmountAcrossStacks() {
        SimpleContainer container = new SimpleContainer(2);
        container.setItem(0, new ItemStack(Items.DIAMOND, 32));
        container.setItem(1, new ItemStack(Items.DIAMOND, 32));

        ItemStack withdrawn = ShopContainers.withdraw(container, Items.DIAMOND, 40);

        assertEquals(40, withdrawn.getCount());
        assertEquals(24, ShopContainers.countMatching(container, Items.DIAMOND));
    }

    @Test
    void depositAddsIntoExistingCompatibleStack() {
        SimpleContainer container = new SimpleContainer(2);
        container.setItem(0, new ItemStack(Items.DIAMOND, 10));

        ItemStack leftover = ShopContainers.deposit(container, new ItemStack(Items.DIAMOND, 20));

        assertTrue(leftover.isEmpty());
        assertEquals(30, ShopContainers.countMatching(container, Items.DIAMOND));
    }

    @Test
    void depositReturnsLeftoverWhenContainerIsFull() {
        SimpleContainer container = new SimpleContainer(1);
        container.setItem(0, new ItemStack(Items.DIAMOND, 64));

        ItemStack leftover = ShopContainers.deposit(container, new ItemStack(Items.DIAMOND, 5));

        assertEquals(5, leftover.getCount());
    }

    @Test
    void findAdjacentChestReturnsTheSingleNeighboringChest() {
        Level level = mock(Level.class);
        BlockPos signPos = new BlockPos(0, 64, 0);
        BlockPos chestPos = signPos.relative(Direction.NORTH);
        ChestBlockEntity chest = mock(ChestBlockEntity.class);
        when(level.getBlockEntity(chestPos)).thenReturn(chest);

        Optional<BlockPos> result = ShopContainers.findAdjacentChest(level, signPos);

        assertTrue(result.isPresent());
        assertEquals(chestPos, result.get());
    }

    @Test
    void findAdjacentChestIsEmptyWithNoNeighboringChest() {
        Level level = mock(Level.class);
        BlockPos signPos = new BlockPos(0, 64, 0);

        assertFalse(ShopContainers.findAdjacentChest(level, signPos).isPresent());
    }

    @Test
    void findAdjacentChestIsEmptyWithTwoNeighboringChests() {
        Level level = mock(Level.class);
        BlockPos signPos = new BlockPos(0, 64, 0);
        ChestBlockEntity chest = mock(ChestBlockEntity.class);
        when(level.getBlockEntity(signPos.relative(Direction.NORTH))).thenReturn(chest);
        when(level.getBlockEntity(signPos.relative(Direction.SOUTH))).thenReturn(chest);

        assertFalse(ShopContainers.findAdjacentChest(level, signPos).isPresent(),
                "a shop needs exactly one backing chest, not several - ambiguous otherwise");
    }

    @Test
    void findAdjacentChestIgnoresNonChestBlockEntities() {
        Level level = mock(Level.class);
        BlockPos signPos = new BlockPos(0, 64, 0);
        BlockEntity notAChest = mock(SignBlockEntity.class);
        when(level.getBlockEntity(signPos.relative(Direction.NORTH))).thenReturn(notAChest);

        assertFalse(ShopContainers.findAdjacentChest(level, signPos).isPresent());
    }
}
