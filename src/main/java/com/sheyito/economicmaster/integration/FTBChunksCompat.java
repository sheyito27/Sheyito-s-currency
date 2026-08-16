package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.EconomicMaster;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

import java.util.UUID;

/**
 * Pure soft-dependency gate: this class itself never references any FTB Chunks/FTB Library type,
 * so it is always safe to load. When FTB Chunks is present, it delegates to
 * {@link FtbChunksIntegration} (which does reference FTB Chunks'/FTB Library's real classes) so
 * that class is never touched - and its bytecode never verified/loaded by the JVM - when FTB
 * Chunks is absent. {@code EconomicMasterScheduler} and {@code ServerLifecycleHandler} (both
 * always loaded, FTB Chunks or not) only ever call through here, never {@code FtbChunksIntegration}
 * directly - same rule as {@link #logDetection()}.
 */
public final class FTBChunksCompat {

    private static final String FTBCHUNKS_MODID = "ftbchunks";

    private FTBChunksCompat() {
    }

    public static void logDetection() {
        if (ModList.get().isLoaded(FTBCHUNKS_MODID)) {
            FtbChunksIntegration.register();
        } else {
            EconomicMaster.LOGGER.info("FTB Chunks no detectado: Sheyito's currency funciona de forma independiente (sin cobro de reclamo de chunks).");
        }
    }

    /** No-op if FTB Chunks isn't installed - safe to call unconditionally from the scheduler. */
    public static void processForceLoadRent(MinecraftServer server) {
        if (ModList.get().isLoaded(FTBCHUNKS_MODID)) {
            FtbChunksIntegration.processForceLoadRent(server);
        }
    }

    /** No-op if FTB Chunks isn't installed - safe to call unconditionally on every login. */
    public static void applyPendingForceUnload(ServerPlayer player) {
        if (ModList.get().isLoaded(FTBCHUNKS_MODID)) {
            FtbChunksIntegration.applyPendingForceUnloadIfNeeded(player);
        }
    }

    /** No-op if FTB Chunks isn't installed - backs {@code /sc rent force}. */
    public static void forceProcessForceLoadRent(MinecraftServer server, UUID uuid) {
        if (ModList.get().isLoaded(FTBCHUNKS_MODID)) {
            FtbChunksIntegration.forceProcessForceLoadRent(server, uuid);
        }
    }
}
