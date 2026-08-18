package com.sheyito.economicmaster.liquidation;

import com.sheyito.economicmaster.EconomicMaster;
import com.sheyito.economicmaster.config.ConfigManager;
import net.minecraft.commands.arguments.EntityAnchorArgument;
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
import net.minecraft.world.entity.player.Player;
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
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;

import java.util.function.Predicate;

/**
 * "Puesto de subastas": a lectern-triggered multiblock (lectern row + a 2-post/roof gate one row
 * over + a lone 3rd column another row back - the exact shape confirmed against the raw block
 * dump in the server log, see docs/features/embargoDeudas.md) that spawns a frozen, invulnerable
 * villager NPC. Interacting with that villager is the ONLY way to pick which pooled item goes up
 * for bid ({@link AuctionPoolManager#startAuction}) - confirmed with the user against the earlier
 * "opens automatically" design.
 *
 * <p>Detection mirrors vanilla's own golem-summoning trick ({@code CarvedPumpkinBlock}, verified
 * by decompiling it): a {@link BlockPatternBuilder} pattern, checked when the trigger block (the
 * lectern) is placed. Unlike golem summoning, the structure's blocks are NOT consumed - the puesto
 * stays standing.
 *
 * <p><b>Orientation - {@link #findInAnyHorizontalOrientation}, not the stock {@link
 * BlockPattern#find}.</b> {@code find(level, pos)} only searches a box that extends forward
 * (+X/+Y/+Z) from {@code pos}, tried against every finger/thumb rotation - fine for a shape like
 * the Iron Golem, whose trigger (the pumpkin) sits at a symmetric, extremal corner of the pattern
 * so the forward-only box always reaches it regardless of which way it's built. This stand's
 * lectern does not: it sits mid-height, one aisle in from the structure's front. Tested in game,
 * only the orientations where that specific offset happened to land inside the forward-only box
 * matched (confirmed by hand: with this pattern's dimensions that's facing south or west) - the
 * other two directions silently failed {@code find()} every time. Since the stand is always built
 * upright (thumb fixed to {@link Direction#UP} - nobody builds a lectern stand sideways or
 * upside-down), the fix only needs to search both signs of the horizontal offset - the vertical
 * one is already always reachable forward from an upright thumb.
 *
 * <p>The villager tracks the nearest player ({@link #onVillagerTick}) instead of facing a fixed
 * direction - {@code setNoAi(true)} (see {@link #onLecternPlaced}) skips the goal selector
 * entirely, including vanilla's own look-at-player goal, so nothing turns its head unless we do
 * it ourselves. Doing it by nearest-player search rather than reading the matched pattern's
 * {@link BlockPattern.BlockPatternMatch#getForwards()} means it works the same regardless of which
 * way the player oriented the structure - confirmed with the user, who didn't want the facing
 * tied to build orientation.
 */
public class AuctionStandListener {

    /** Tag on the spawned villager's persistent data marking it as one of ours - see
     * {@link #onVillagerInteract}. Plain NBT flag instead of a custom entity type: far less code,
     * and every vanilla system (rendering, saving, zombie infection chance, ...) keeps working on
     * a real {@link Villager} with no extra effort. */
    private static final String STAND_VILLAGER_TAG = "sheyito_auction_stand";

    /** How close a player needs to be for the stand's villager to turn and look at them. */
    private static final double LOOK_AT_PLAYER_RANGE = 8.0;

    /** Re-aim every 4 ticks (5x/second) instead of every tick - plenty smooth for a head turn,
     * a quarter of the nearest-player lookups. */
    private static final int LOOK_AT_INTERVAL_TICKS = 4;

    /** The only orientations a real build can have - always upright, see {@link
     * #findInAnyHorizontalOrientation}. */
    private static final Direction[] HORIZONTAL_FACINGS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

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
        BlockPattern.BlockPatternMatch match = findInAnyHorizontalOrientation(pattern, level, event.getPos());
        EconomicMaster.LOGGER.info("[puesto de subastas] atril colocado en {}, encaja={}", event.getPos(), match != null);
        if (match == null) {
            dumpSurroundings(level, event.getPos(), standBlock);
            return;
        }

        BlockPos spawnPos = match.getBlock(1, 2, 1).getPos();
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

    /**
     * Makes the stand's villager turn to face whoever is closest, every {@link
     * #LOOK_AT_INTERVAL_TICKS} ticks - {@code setNoAi(true)} means vanilla's own look-at-player
     * goal never runs, so without this the villager stares in whatever direction it spawned
     * facing forever. {@link EntityTickEvent.Post} fires for every entity every tick (both sides -
     * {@code level().isClientSide()} keeps this server-only), so filtering to tagged villagers
     * here is cheap and needs no separate registry of "which villagers are ours".
     */
    @SubscribeEvent
    public void onVillagerTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof Villager villager) || villager.level().isClientSide()) {
            return;
        }
        if (!villager.getPersistentData().getBoolean(STAND_VILLAGER_TAG)) {
            return;
        }
        if (villager.tickCount % LOOK_AT_INTERVAL_TICKS != 0) {
            return;
        }
        Player nearest = villager.level().getNearestPlayer(villager, LOOK_AT_PLAYER_RANGE);
        if (nearest != null) {
            villager.lookAt(EntityAnchorArgument.Anchor.EYES, nearest.getEyePosition());
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
     * TEMPORARY debugging aid, not part of the permanent design. Makes ZERO assumptions about
     * rotation/offset math (that logic lives in {@link #buildPattern} and could itself be the bug)
     * - just prints the raw block at every position in a 5x5 box around the lectern, one line per
     * height layer, dy=-1 (below the lectern) through dy=4 (above the roof). Read bottom-to-top:
     * dy=0 should show the lectern ('L') with wool ('W') on both sides on the same line; dy=1/dy=2
     * should show the stand block ('S') framing an empty gap ('.') where the lectern column is;
     * dy=3 should be a solid block of 'S'. Anything else prints the real block's registry name.
     */
    private static void dumpSurroundings(ServerLevel level, BlockPos lecternPos, Block standBlock) {
        EconomicMaster.LOGGER.info("[puesto de subastas] volcado alrededor de {} (bloque de pie configurado: {})",
                lecternPos, BuiltInRegistries.BLOCK.getKey(standBlock));
        for (int dy = -1; dy <= 4; dy++) {
            StringBuilder line = new StringBuilder();
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    BlockState state = level.getBlockState(lecternPos.offset(dx, dy, dz));
                    line.append(abbreviate(state, standBlock)).append(' ');
                }
                line.append("| ");
            }
            EconomicMaster.LOGGER.info("[puesto de subastas] dy={}: {}", dy, line.toString().trim());
        }
        EconomicMaster.LOGGER.info(
                "[puesto de subastas] cada bloque de las 5 columnas es dx=-2..2 (eje X); cada grupo separado por '|' es dz=-2..2 (eje Z)");
    }

    private static String abbreviate(BlockState state, Block standBlock) {
        if (state.isAir()) {
            return "....";
        }
        if (state.is(standBlock)) {
            return "STND";
        }
        if (state.is(Blocks.RED_WOOL)) {
            return "WOOL";
        }
        if (state.is(Blocks.LECTERN)) {
            return "LECT";
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.length() > 4 ? path.substring(0, 4).toUpperCase() : path.toUpperCase();
    }

    /**
     * Searches for {@code pattern} anchored at {@code triggerPos} (the placed lectern), trying
     * every upright horizontal facing - the fix for the "only works facing one way" bug described
     * above. {@code BlockPattern#matches(LevelReader, BlockPos, Direction, Direction)} checks one
     * exact (origin corner, finger, thumb) combination; this tries all of them within reach.
     *
     * <p>{@code thumb} stays fixed to {@link Direction#UP} (never searches sideways/upside-down
     * builds - not a real scenario here) and {@code dy} only searches forward (0..size-1) since an
     * upright thumb always keeps that offset non-negative regardless of which way the structure
     * faces. {@code dx}/{@code dz} search BOTH signs, since which one needs to go negative depends
     * on the horizontal facing being tested - that is exactly what {@code find()} gets wrong by
     * only ever searching forward.
     *
     * <p>Package-private (not {@code private}) so {@code AuctionStandListenerTest} can drive it
     * directly against a fake {@link ServerLevel} - the rest of this class genuinely needs a real
     * level (see the class javadoc), but this search is pure geometry over
     * {@link ServerLevel#getBlockState}/{@link ServerLevel#hasChunkAt}, which a mock can stand in
     * for just fine.
     */
    static BlockPattern.BlockPatternMatch findInAnyHorizontalOrientation(
            BlockPattern pattern, ServerLevel level, BlockPos triggerPos) {
        int size = Math.max(Math.max(pattern.getWidth(), pattern.getHeight()), pattern.getDepth());
        for (int dy = 0; dy < size; dy++) {
            for (int dx = -(size - 1); dx < size; dx++) {
                for (int dz = -(size - 1); dz < size; dz++) {
                    BlockPos candidate = triggerPos.offset(dx, dy, dz);
                    for (Direction finger : HORIZONTAL_FACINGS) {
                        BlockPattern.BlockPatternMatch match = pattern.matches(level, candidate, finger, Direction.UP);
                        if (match != null) {
                            return match;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static Block resolveStandBlock() {
        String id = ConfigManager.liquidation().auctionStandBlockId;
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        return block == Blocks.AIR ? Blocks.POLISHED_ANDESITE : block;
    }

    /**
     * Three aisles deep, 4 rows (roof, two interior rows the villager stands in, floor) x 3
     * columns - the exact shape read off {@code dumpSurroundings}' raw block dump once it was
     * clear the earlier "lectern embedded in the gate" pattern didn't match what got built:
     * aisle 0 is the lectern's own row (just the lectern on a wool base, nothing beside it),
     * aisle 1 one row over is the actual gate (2 stand-block posts + roof around a 2-block gap,
     * wool at the base between the posts), aisle 2 another row over is a lone 3rd stand-block
     * column. 'S' = the configured stand block, 'W' = red wool, 'L' = the lectern, ' ' = must be
     * open air (the gap the villager appears in), '?' = don't care.
     *
     * <p>Package-private for {@code AuctionStandListenerTest} - see {@link
     * #findInAnyHorizontalOrientation}.
     */
    static BlockPattern buildPattern(Block standBlock) {
        Predicate<BlockInWorld> stand = BlockInWorld.hasState(BlockStatePredicate.forBlock(standBlock));
        Predicate<BlockInWorld> wool = BlockInWorld.hasState(BlockStatePredicate.forBlock(Blocks.RED_WOOL));
        Predicate<BlockInWorld> lectern = BlockInWorld.hasState(BlockStatePredicate.forBlock(Blocks.LECTERN));
        Predicate<BlockInWorld> air = block -> block.getState().isAir();
        Predicate<BlockInWorld> anything = block -> true;

        return BlockPatternBuilder.start()
                .aisle("???", "???", "?L?", "?W?")
                .aisle("SSS", "S S", "S S", "SWS")
                .aisle("?S?", "?S?", "?S?", "?S?")
                .where('S', stand)
                .where('W', wool)
                .where('L', lectern)
                .where(' ', air)
                .where('?', anything)
                .build();
    }
}
