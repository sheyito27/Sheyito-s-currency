package com.sheyito.economicmaster.liquidation;

import com.sheyito.economicmaster.TestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the "only fits facing one way" bug: vanilla's {@link BlockPattern#find}
 * only searches a box that extends forward (+X/+Y/+Z) from the trigger position, which happens to
 * miss this stand's lectern (mid-height, one aisle in from the front) whenever the structure is
 * built facing north or east - confirmed in game, see {@code AuctionStandListener}'s class javadoc
 * and docs/features/embargoDeudas.md. Everything else about this listener genuinely needs a real
 * {@link net.minecraft.server.level.ServerLevel} and isn't unit-testable here, but the pattern
 * search itself only touches {@code getBlockState}/{@code hasChunkAt}, which a mock covers fine.
 */
class AuctionStandListenerTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    /**
     * Places a fake world representing the puesto de subastas built facing {@code finger} (thumb
     * always {@link Direction#UP} - real builds are always upright), anchored so that the pattern's
     * local origin (0,0,0) sits at {@code anchor}. Returns the world position of the lectern cell
     * (local coords width=1, height=2, depth=0 in {@code buildPattern}) - i.e. the trigger position
     * {@code AuctionStandListener#onLecternPlaced} would see.
     */
    private static BlockPos placeStand(Map<BlockPos, BlockState> world, BlockPos anchor, Direction finger, Block standBlock) {
        Direction thumb = Direction.UP;
        // Same right-hand-rule cross product BlockPattern#translateAndRotate uses internally.
        Direction palm = crossProduct(finger, thumb);

        // local (width i, height j, depth k) -> world position, matching BlockPattern's own
        // translateAndRotate(pos, finger, thumb, palmOffset=i, thumbOffset=j, fingerOffset=k).
        java.util.function.BiFunction<BlockPos, int[], BlockPos> at = (base, ijk) -> base.offset(
                thumb.getStepX() * -ijk[1] + palm.getStepX() * ijk[0] + finger.getStepX() * ijk[2],
                thumb.getStepY() * -ijk[1] + palm.getStepY() * ijk[0] + finger.getStepY() * ijk[2],
                thumb.getStepZ() * -ijk[1] + palm.getStepZ() * ijk[0] + finger.getStepZ() * ijk[2]);

        BlockState stand = standBlock.defaultBlockState();
        BlockState wool = Blocks.RED_WOOL.defaultBlockState();
        BlockState lectern = Blocks.LECTERN.defaultBlockState();

        // aisle k=0: lectern row (only the lectern cell and the wool under it are concrete).
        world.put(at.apply(anchor, new int[]{1, 2, 0}), lectern);
        world.put(at.apply(anchor, new int[]{1, 3, 0}), wool);

        // aisle k=1: the gate - roof, two side-posts flanking an air gap, wool at the floor gap.
        for (int i = 0; i <= 2; i++) {
            world.put(at.apply(anchor, new int[]{i, 0, 1}), stand);
        }
        world.put(at.apply(anchor, new int[]{0, 1, 1}), stand);
        world.put(at.apply(anchor, new int[]{2, 1, 1}), stand);
        world.put(at.apply(anchor, new int[]{0, 2, 1}), stand);
        world.put(at.apply(anchor, new int[]{2, 2, 1}), stand);
        world.put(at.apply(anchor, new int[]{0, 3, 1}), stand);
        world.put(at.apply(anchor, new int[]{1, 3, 1}), wool);
        world.put(at.apply(anchor, new int[]{2, 3, 1}), stand);

        // aisle k=2: the lone third column, centered, full height.
        for (int j = 0; j <= 3; j++) {
            world.put(at.apply(anchor, new int[]{1, j, 2}), stand);
        }

        return at.apply(anchor, new int[]{1, 2, 0});
    }

    private static Direction crossProduct(Direction a, Direction b) {
        int x = a.getStepY() * b.getStepZ() - a.getStepZ() * b.getStepY();
        int y = a.getStepZ() * b.getStepX() - a.getStepX() * b.getStepZ();
        int z = a.getStepX() * b.getStepY() - a.getStepY() * b.getStepX();
        for (Direction d : Direction.values()) {
            if (d.getStepX() == x && d.getStepY() == y && d.getStepZ() == z) {
                return d;
            }
        }
        throw new IllegalArgumentException("no matching direction for cross product of " + a + " and " + b);
    }

    private static ServerLevel fakeLevel(Map<BlockPos, BlockState> world) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.hasChunkAt(any(BlockPos.class))).thenReturn(true);
        when(level.getBlockState(any(BlockPos.class))).thenAnswer(invocation ->
                world.getOrDefault(invocation.getArgument(0), Blocks.AIR.defaultBlockState()));
        return level;
    }

    @Test
    void vanillaFindMissesTheStandWhenBuiltFacingNorth() {
        Block standBlock = Blocks.POLISHED_ANDESITE;
        BlockPattern pattern = AuctionStandListener.buildPattern(standBlock);
        Map<BlockPos, BlockState> world = new HashMap<>();
        BlockPos anchor = new BlockPos(100, 70, 100);
        BlockPos triggerPos = placeStand(world, anchor, Direction.NORTH, standBlock);
        ServerLevel level = fakeLevel(world);

        // Sanity check: the fake world really is a north-facing build, confirmed by vanilla's own
        // exact matcher at the known anchor - if this fails, the bug is in this test, not the fix.
        assertNotNull(pattern.matches(level, anchor, Direction.NORTH, Direction.UP),
                "test setup is wrong if this doesn't match");

        assertNull(pattern.find(level, triggerPos),
                "documents the bug: find()'s forward-only search box misses a north-facing build");
    }

    @Test
    void findInAnyHorizontalOrientationFindsTheStandBuiltFacingNorth() {
        Block standBlock = Blocks.POLISHED_ANDESITE;
        BlockPattern pattern = AuctionStandListener.buildPattern(standBlock);
        Map<BlockPos, BlockState> world = new HashMap<>();
        BlockPos anchor = new BlockPos(100, 70, 100);
        BlockPos triggerPos = placeStand(world, anchor, Direction.NORTH, standBlock);
        ServerLevel level = fakeLevel(world);

        assertNotNull(AuctionStandListener.findInAnyHorizontalOrientation(pattern, level, triggerPos),
                "the fix must find a north-facing build even though vanilla's find() can't");
    }

    @Test
    void findInAnyHorizontalOrientationFindsTheStandBuiltFacingEast() {
        Block standBlock = Blocks.POLISHED_ANDESITE;
        BlockPattern pattern = AuctionStandListener.buildPattern(standBlock);
        Map<BlockPos, BlockState> world = new HashMap<>();
        BlockPos anchor = new BlockPos(100, 70, 100);
        BlockPos triggerPos = placeStand(world, anchor, Direction.EAST, standBlock);
        ServerLevel level = fakeLevel(world);

        assertNotNull(AuctionStandListener.findInAnyHorizontalOrientation(pattern, level, triggerPos),
                "the other orientation that used to fail (see class javadoc) must also be found now");
    }

    @Test
    void findInAnyHorizontalOrientationStillFindsTheStandBuiltFacingWest() {
        // West is one of the two orientations that already worked before the fix (confirmed by the
        // user in game) - this locks in that the fix doesn't regress it.
        Block standBlock = Blocks.POLISHED_ANDESITE;
        BlockPattern pattern = AuctionStandListener.buildPattern(standBlock);
        Map<BlockPos, BlockState> world = new HashMap<>();
        BlockPos anchor = new BlockPos(100, 70, 100);
        BlockPos triggerPos = placeStand(world, anchor, Direction.WEST, standBlock);
        ServerLevel level = fakeLevel(world);

        assertNotNull(AuctionStandListener.findInAnyHorizontalOrientation(pattern, level, triggerPos));
    }
}
