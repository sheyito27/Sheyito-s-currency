package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.EconomicMaster;
import com.sheyito.economicmaster.chunk.ChunkClaimManager;
import com.sheyito.economicmaster.config.ChunkClaimConfig;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import dev.architectury.event.CompoundEventResult;
import dev.ftb.mods.ftbchunks.api.ClaimResult;
import dev.ftb.mods.ftbchunks.api.ClaimedChunk;
import dev.ftb.mods.ftbchunks.api.event.ClaimedChunkEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Every FTB Chunks/FTB Library type reference in the whole mod lives in this one class,
 * deliberately - {@link FTBChunksCompat} only ever calls {@link #register()} from behind a
 * {@code ModList.get().isLoaded("ftbchunks")} check, so this class's bytecode is only ever
 * verified/loaded by the JVM when FTB Chunks is actually present.
 *
 * <p>FTB Chunks' events go through Architectury (same event system FTB Quests uses), not
 * NeoForge's. Two phases are hooked, per {@code ClaimedChunkEvent}'s own javadoc warning that
 * {@code BEFORE_CLAIM} may fire for a <em>simulated</em> operation and must never mutate state:
 * {@code BEFORE_CLAIM} only checks affordability (blocks the claim if insufficient); the actual
 * charge - and the {@link ChunkClaimManager} count increment that drives next time's price -
 * happens in {@code AFTER_CLAIM}, which only fires once the claim is confirmed real.
 *
 * <p>Verified against FTB Chunks' own consuming code ({@code ChunkTeamDataImpl.claim()}): only
 * {@code CompoundEventResult#object()} is read (the wrapped {@link ClaimResult}, via its
 * {@code isSuccess()}) - the interrupt boolean is never inspected. {@code CompoundEventResult.pass()}
 * lets the claim proceed; {@code CompoundEventResult.interruptTrue(ClaimResult.customProblem(...))}
 * blocks it. {@code customProblem} technically expects a translation key, but like the rest of
 * this mod (no lang files anywhere), a literal Spanish message is passed directly - Minecraft
 * falls back to showing the raw text when no translation is registered for it.
 */
final class FtbChunksIntegration {

    private FtbChunksIntegration() {
    }

    static void register() {
        ClaimedChunkEvent.BEFORE_CLAIM.register(FtbChunksIntegration::onBeforeClaim);
        ClaimedChunkEvent.AFTER_CLAIM.register(FtbChunksIntegration::onAfterClaim);
        EconomicMaster.LOGGER.info("Sheyito's currency: integracion con FTB Chunks activada - reclamar un chunk cobra un importe creciente por jugador.");
    }

    /** Check only, never deducts - may fire for a simulated claim attempt. */
    private static CompoundEventResult<ClaimResult> onBeforeClaim(CommandSourceStack source, ClaimedChunk chunk) {
        ServerPlayer player = source.getPlayer();
        ChunkClaimConfig config = ConfigManager.chunkClaim();
        EconomyManager economy = EconomyManager.get();
        ChunkClaimManager claims = ChunkClaimManager.get();

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
        ChunkClaimManager claims = ChunkClaimManager.get();
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
}
