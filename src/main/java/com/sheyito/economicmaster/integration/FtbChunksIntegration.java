package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.EconomicMaster;
import com.sheyito.economicmaster.chunk.ChunkClaimRegistry;
import com.sheyito.economicmaster.config.ChunkClaimConfig;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.RentConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.GameTime;
import com.sheyito.economicmaster.util.Money;
import dev.architectury.event.CompoundEventResult;
import dev.ftb.mods.ftbchunks.api.ChunkTeamData;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.event.ClaimedChunkEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.UUID;

/**
 * Every FTB Chunks/FTB Library type reference in the whole mod lives in this one class,
 * deliberately - {@link FTBChunksCompat} only ever calls {@link #register()} from behind a
 * {@code ModList.get().isLoaded("ftbchunks")} check, so this class's bytecode is only ever
 * verified/loaded by the JVM when FTB Chunks is actually present.
 *
 * <p>FTB Chunks' events go through Architectury (same event system FTB Quests uses), not
 * NeoForge's. Three claim-side phases are hooked, per {@code ClaimedChunkEvent}'s own javadoc
 * warning that "before" events may fire for a <em>simulated</em> operation and must never mutate
 * state: {@code BEFORE_CLAIM} only checks affordability (blocks the claim if insufficient); the
 * actual charge - and the {@link ChunkClaimRegistry} count increment that drives next time's
 * price - happens in {@code AFTER_CLAIM}, which only fires once the claim is confirmed real.
 * {@code AFTER_UNCLAIM} decrements that same count, so releasing a chunk brings the price of the
 * next claim back down - the count is a live "chunks held now", not a lifetime total.
 *
 * <p>Verified against FTB Chunks' own consuming code ({@code ChunkTeamDataImpl.claim()}): only
 * {@code CompoundEventResult#object()} is read (the wrapped {@link ClaimResult}, via its
 * {@code isSuccess()}) - the interrupt boolean is never inspected. {@code CompoundEventResult.pass()}
 * lets the claim proceed; {@code CompoundEventResult.interruptTrue(ClaimResult.customProblem(...))}
 * blocks it. {@code customProblem} technically expects a translation key, but like the rest of
 * this mod (no lang files anywhere), a literal Spanish message is passed directly - Minecraft
 * falls back to showing the raw text when no translation is registered for it.
 *
 * <p>{@code AFTER_LOAD}/{@code AFTER_UNLOAD} (same event shape, verified with {@code javap}
 * against the real jar) drive {@link ChunkClaimRegistry}'s force-loaded count, which
 * {@link #processForceLoadRent} bills every {@code intervalGameDays} - never at load/unload time
 * itself, force-loading stays free to start. If a player can't cover the bill, every one of their
 * force-loaded chunks is unloaded (all-or-nothing, confirmed with the user): immediately via
 * {@link #unloadAllForceLoadedChunks} if they're online, or deferred to
 * {@link #applyPendingForceUnloadIfNeeded} on their next login otherwise - there is no way to
 * build the {@code CommandSourceStack} an unload needs without a live {@code ServerPlayer}.
 * {@code ClaimedChunkManager.getOrCreateData(ServerPlayer)} resolves the owning
 * {@code ChunkTeamData} straight from the player, so none of this needs to import or reason about
 * FTB Teams.
 */
final class FtbChunksIntegration {

    private FtbChunksIntegration() {
    }

    static void register() {
        ClaimedChunkEvent.BEFORE_CLAIM.register(FtbChunksIntegration::onBeforeClaim);
        ClaimedChunkEvent.AFTER_CLAIM.register(FtbChunksIntegration::onAfterClaim);
        ClaimedChunkEvent.AFTER_UNCLAIM.register(FtbChunksIntegration::onAfterUnclaim);
        ClaimedChunkEvent.AFTER_LOAD.register(FtbChunksIntegration::onAfterLoad);
        ClaimedChunkEvent.AFTER_UNLOAD.register(FtbChunksIntegration::onAfterUnload);
        EconomicMaster.LOGGER.info("Sheyito's currency: integracion con FTB Chunks activada - reclamar un chunk cobra un importe creciente por jugador, y el force-load tiene renta semanal.");
    }

    /** Check only, never deducts - may fire for a simulated claim attempt. */
    private static CompoundEventResult<ClaimResult> onBeforeClaim(CommandSourceStack source, ClaimedChunk chunk) {
        ServerPlayer player = source.getPlayer();
        ChunkClaimConfig config = ConfigManager.chunkClaim();
        EconomyManager economy = EconomyManager.get();
        ChunkClaimRegistry claims = ChunkClaimRegistry.get();

        if (player == null || economy == null || claims == null || !ChunkClaimLogic.isEnabled(config)) {
            return CompoundEventResult.pass();
        }

        int alreadyClaimed = claims.getClaimCount(player.getUUID());
        if (ChunkClaimLogic.canAfford(economy, config, player.getUUID(), alreadyClaimed)) {
            return CompoundEventResult.pass();
        }

        // Kept deliberately short (no full Money.format()) - this text renders inside FTB Chunks'
        // own claim GUI panel, which has very little horizontal room and does not wrap.
        return CompoundEventResult.interruptTrue(ClaimResult.customProblem(
                "Saldo insuficiente (" + Math.round(ChunkClaimLogic.costFor(alreadyClaimed)) + " SC)."));
    }

    /** Charges the claim cost and bumps the player's count, only once the claim is confirmed real. */
    private static void onAfterClaim(CommandSourceStack source, ClaimedChunk chunk) {
        ServerPlayer player = source.getPlayer();
        ChunkClaimConfig config = ConfigManager.chunkClaim();
        EconomyManager economy = EconomyManager.get();
        ChunkClaimRegistry claims = ChunkClaimRegistry.get();
        if (player == null || economy == null || claims == null || !ChunkClaimLogic.isEnabled(config)) {
            return;
        }

        int alreadyClaimed = claims.getClaimCount(player.getUUID());
        if (ChunkClaimLogic.chargeClaim(economy, config, player.getUUID(), alreadyClaimed)) {
            claims.incrementClaimCount(player.getUUID());
            player.sendSystemMessage(Component.literal("§6[Sheyito's currency] §f-" + Money.format(ChunkClaimLogic.costFor(alreadyClaimed))
                    + " (chunk #" + (alreadyClaimed + 1) + " reclamado)."));
        }
    }

    /**
     * Brings the count back down when a chunk is genuinely unclaimed, so the next claim is
     * priced off how many chunks the player holds now, not a lifetime total that only grows.
     * No refund - releasing a chunk is free, same as {@code /sc dimension lock} doesn't refund.
     */
    private static void onAfterUnclaim(CommandSourceStack source, ClaimedChunk chunk) {
        ServerPlayer player = source.getPlayer();
        ChunkClaimConfig config = ConfigManager.chunkClaim();
        ChunkClaimRegistry claims = ChunkClaimRegistry.get();
        if (player == null || claims == null || !ChunkClaimLogic.isEnabled(config)) {
            return;
        }

        claims.decrementClaimCount(player.getUUID());
    }

    private static void onAfterLoad(CommandSourceStack source, ClaimedChunk chunk) {
        ServerPlayer player = source.getPlayer();
        ChunkClaimRegistry claims = ChunkClaimRegistry.get();
        if (player == null || claims == null || source.getServer() == null) {
            return;
        }
        claims.incrementLoadedCount(player.getUUID(), GameTime.currentDay(source.getServer()));
    }

    private static void onAfterUnload(CommandSourceStack source, ClaimedChunk chunk) {
        ServerPlayer player = source.getPlayer();
        ChunkClaimRegistry claims = ChunkClaimRegistry.get();
        if (player == null || claims == null) {
            return;
        }
        claims.decrementLoadedCount(player.getUUID());
    }

    /**
     * Called from {@code EconomicMasterScheduler} (via {@code FTBChunksCompat}, never directly)
     * every ~30s - day precision is all this needs, same as subscription billing. Force-loading
     * itself was always free; this is the only place rent for it is ever charged.
     */
    static void processForceLoadRent(MinecraftServer server) {
        ChunkClaimRegistry claims = ChunkClaimRegistry.get();
        EconomyManager economy = EconomyManager.get();
        RentConfig config = ConfigManager.rent();
        if (claims == null || economy == null || config == null || !config.enabled) {
            return;
        }

        long currentDay = GameTime.currentDay(server);
        for (UUID uuid : claims.playersDueForForceLoadRent(currentDay, config.intervalGameDays)) {
            chargeOrUnload(server, claims, economy, config, uuid, currentDay);
        }
    }

    /**
     * Admin-only testing tool for {@code /sc rent forzar} - bills this one player's force-load
     * rent right now, ignoring whether {@code intervalGameDays} has actually elapsed. A no-op if
     * they have nothing force-loaded (nothing to bill). Same math and side effects (including
     * all-or-nothing auto-unload on failure) as the normal per-player pass inside
     * {@link #processForceLoadRent}.
     */
    static void forceProcessForceLoadRent(MinecraftServer server, UUID uuid) {
        ChunkClaimRegistry claims = ChunkClaimRegistry.get();
        EconomyManager economy = EconomyManager.get();
        RentConfig config = ConfigManager.rent();
        if (claims == null || economy == null || config == null || !config.enabled || claims.getLoadedCount(uuid) <= 0) {
            return;
        }

        chargeOrUnload(server, claims, economy, config, uuid, GameTime.currentDay(server));
    }

    private static void chargeOrUnload(MinecraftServer server, ChunkClaimRegistry claims, EconomyManager economy,
                                        RentConfig config, UUID uuid, long currentDay) {
        int loaded = claims.getLoadedCount(uuid);
        double rent = ChunkClaimRegistry.forceLoadRentFor(config.forceLoadRentBase, loaded);
        boolean paid = economy.take(uuid, rent);
        claims.markForceLoadRentChecked(uuid, currentDay);

        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (paid) {
            if (player != null) {
                player.sendSystemMessage(Component.literal("§6[Sheyito's currency] §f-" + Money.format(rent)
                        + " (renta de " + loaded + " chunk(s) force-loaded)."));
            }
            return;
        }

        if (player != null) {
            unloadAllForceLoadedChunks(player);
            claims.clearLoadedChunks(uuid);
            player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fNo cubriste la renta de force-load ("
                    + Money.format(rent) + ") - se descargaron todos tus chunks force-loaded."));
        } else {
            claims.markPendingForceUnload(uuid);
        }
    }

    /**
     * Called from {@code ServerLifecycleHandler.onPlayerLoggedIn} (via {@code FTBChunksCompat})
     * - applies an unload that couldn't happen at billing time because the player was offline and
     * there was no {@link ServerPlayer} to build a {@link CommandSourceStack} from.
     */
    static void applyPendingForceUnloadIfNeeded(ServerPlayer player) {
        ChunkClaimRegistry claims = ChunkClaimRegistry.get();
        if (claims == null || !claims.hasPendingForceUnload(player.getUUID())) {
            return;
        }
        unloadAllForceLoadedChunks(player);
        claims.clearLoadedChunks(player.getUUID());
        player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fSe descargaron todos tus chunks force-loaded: "
                + "no cubriste la renta mientras estabas desconectado."));
    }

    /** {@code ClaimedChunkManager.getOrCreateData(ServerPlayer)} resolves the owning
     * {@link ChunkTeamData} directly from the player - no FTB Teams type ever needs to be
     * imported to find or unload their force-loaded chunks. */
    private static void unloadAllForceLoadedChunks(ServerPlayer player) {
        ChunkTeamData teamData = FTBChunksAPI.api().getManager().getOrCreateData(player);
        CommandSourceStack source = player.createCommandSourceStack();
        for (ClaimedChunk chunk : List.copyOf(teamData.getForceLoadedChunks())) {
            chunk.unload(source);
        }
    }
}
