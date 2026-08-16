package com.sheyito.economicmaster.embargo;

import com.sheyito.economicmaster.EconomicMaster;
import com.sheyito.economicmaster.config.ConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.block.state.pattern.BlockPattern;
import net.minecraft.world.level.block.state.pattern.BlockPatternBuilder;
import net.minecraft.world.level.block.state.predicate.BlockStatePredicate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * "Puesto de subastas": a lectern-triggered multiblock (3 columns + a roof around a 2-block gap,
 * same shape the user drew and confirmed - see docs/features/embargoDeudas.md) that spawns a
 * frozen, invulnerable villager NPC. Interacting with that villager is the ONLY way to pick which
 * pooled item goes up for bid ({@link AuctionPoolManager#startAuction}) - confirmed with the user
 * against the earlier "opens automatically" design.
 *
 * <p>Detection mirrors vanilla's own golem-summoning trick ({@code CarvedPumpkinBlock}, verified
 * by decompiling it): a {@link BlockPatternBuilder} pattern, checked when the trigger block (the
 * lectern) is placed, tried at every rotation/offset by {@link BlockPattern#find}. Unlike golem
 * summoning, the structure's blocks are NOT consumed - the puesto stays standing.
 */
public class AuctionStandListener {

    /** Tag on the spawned villager's persistent data marking it as one of ours - see
     * {@link #onVillagerInteract}. Plain NBT flag instead of a custom entity type: far less code,
     * and every vanilla system (rendering, saving, zombie infection chance, ...) keeps working on
     * a real {@link Villager} with no extra effort. */
    private static final String STAND_VILLAGER_TAG = "sheyito_auction_stand";

    @SubscribeEvent
    public void onLecternPlaced(BlockEvent.EntityPlaceEvent event) {
        if (!event.getPlacedBlock().is(Blocks.LECTERN)) {
            return;
        }
        LevelAccessor levelAccessor = event.getLevel();
        if (!(levelAccessor instanceof ServerLevel level)) {
            return;
        }

        Block standBlock = resolveStandBlock();
        BlockPattern pattern = buildPattern(standBlock);
        BlockPattern.BlockPatternMatch match = pattern.find(level, event.getPos());
        EconomicMaster.LOGGER.info("[puesto de subastas] atril colocado en {}, encaja={}", event.getPos(), match != null);
        if (match == null) {
            logDiagnostics(level, event.getPos(), standBlock);
            return;
        }

        BlockPos spawnPos = match.getBlock(1, 2, 0).getPos();
        Villager villager = EntityType.VILLAGER.create(level);
        if (villager == null) {
            return;
        }
        villager.moveTo(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0.0F, 0.0F);
        villager.setNoAi(true);
        villager.setInvulnerable(true);
        villager.setPersistenceRequired();
        villager.getPersistentData().putBoolean(STAND_VILLAGER_TAG, true);
        level.addFreshEntity(villager);

        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                spawnPos.getX() + 0.5, spawnPos.getY() + 1.0, spawnPos.getZ() + 0.5, 20, 0.3, 0.5, 0.3, 0.0);
        level.playSound(null, spawnPos, SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 1.0F, 1.0F);

        if (event.getEntity() instanceof ServerPlayer placer) {
            placer.sendSystemMessage(Component.literal(
                    "§a[Sheyito's currency] §fPuesto de subastas creado - habla con el aldeano para elegir qué se subasta."));
        }
    }

    @SubscribeEvent
    public void onVillagerInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof Villager villager) || !villager.getPersistentData().getBoolean(STAND_VILLAGER_TAG)) {
            return;
        }
        event.setCanceled(true);
        if (event.getHand() != InteractionHand.MAIN_HAND || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        AuctionStandSelectionMenu.open(player);
    }

    /**
     * TEMPORARY debugging aid, not part of the permanent design - {@link BlockPattern#find} just
     * returns null on any mismatch, with no way to ask it *what* failed. This re-checks every
     * required cell directly (bypassing {@code BlockPattern} entirely) against all 4 horizontal
     * orientations, logs whichever orientation has the fewest mismatches, and lists exactly which
     * positions/blocks are wrong - so a failed build can be diagnosed from the server log instead
     * of guessing blind.
     */
    private static void logDiagnostics(ServerLevel level, BlockPos lecternPos, Block standBlock) {
        record Cell(int right, int back, int up, char type) {
        }
        String[] frontAisle = {"SSS", "S S", "S S", "WLW"};
        String[] backAisle = {"?S?", "?S?", "?S?", "???"};
        List<Cell> cells = new ArrayList<>();
        for (int row = 0; row < 4; row++) {
            int up = 3 - row;
            for (int col = 0; col < 3; col++) {
                int right = col - 1;
                char front = frontAisle[row].charAt(col);
                if (front != '?') {
                    cells.add(new Cell(right, 0, up, front));
                }
                char back = backAisle[row].charAt(col);
                if (back != '?') {
                    cells.add(new Cell(right, 1, up, back));
                }
            }
        }

        int bestMismatches = Integer.MAX_VALUE;
        Direction bestRight = null;
        List<String> bestDetails = List.of();
        for (Direction right : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            Direction back = right.getClockWise();
            List<String> details = new ArrayList<>();
            for (Cell cell : cells) {
                BlockPos pos = lecternPos.relative(right, cell.right()).relative(back, cell.back()).above(cell.up());
                BlockState actual = level.getBlockState(pos);
                boolean ok = switch (cell.type()) {
                    case 'S' -> actual.is(standBlock);
                    case 'W' -> actual.is(Blocks.RED_WOOL);
                    case 'L' -> actual.is(Blocks.LECTERN);
                    case ' ' -> actual.isAir();
                    default -> true;
                };
                if (!ok) {
                    details.add(pos + " esperaba '" + cell.type() + "' pero hay " + actual.getBlock());
                }
            }
            if (details.size() < bestMismatches) {
                bestMismatches = details.size();
                bestRight = right;
                bestDetails = details;
            }
        }

        EconomicMaster.LOGGER.info("[puesto de subastas] mejor orientacion probada: right={} - {} fallo(s) de {} celdas requeridas",
                bestRight, bestMismatches, cells.size());
        for (String detail : bestDetails) {
            EconomicMaster.LOGGER.info("[puesto de subastas]   {}", detail);
        }
    }

    private static Block resolveStandBlock() {
        String id = ConfigManager.embargo().auctionStandBlockId;
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        return block == Blocks.AIR ? Blocks.POLISHED_ANDESITE : block;
    }

    /**
     * Two aisles (front, with the lectern in the doorway; back, with the third column) x 4 rows
     * (roof, two interior rows the villager stands in, floor) x 3 columns. 'S' = the configured
     * stand block, 'W' = red wool, 'L' = the lectern, ' ' = must be open air (the doorway gap),
     * '?' = don't care (outside the structure's footprint).
     */
    private static BlockPattern buildPattern(Block standBlock) {
        Predicate<BlockInWorld> stand = BlockInWorld.hasState(BlockStatePredicate.forBlock(standBlock));
        Predicate<BlockInWorld> wool = BlockInWorld.hasState(BlockStatePredicate.forBlock(Blocks.RED_WOOL));
        Predicate<BlockInWorld> lectern = BlockInWorld.hasState(BlockStatePredicate.forBlock(Blocks.LECTERN));
        Predicate<BlockInWorld> air = block -> block.getState().isAir();
        Predicate<BlockInWorld> anything = block -> true;

        return BlockPatternBuilder.start()
                .aisle("SSS", "S S", "S S", "WLW")
                .aisle("?S?", "?S?", "?S?", "???")
                .where('S', stand)
                .where('W', wool)
                .where('L', lectern)
                .where(' ', air)
                .where('?', anything)
                .build();
    }
}
