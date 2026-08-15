package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.EconomicMaster;
import net.neoforged.fml.ModList;

/**
 * Pure soft-dependency gate: this class itself never references any FTB Chunks/FTB Library type,
 * so it is always safe to load. When FTB Chunks is present, it delegates to
 * {@link FtbChunksIntegration} (which does reference FTB Chunks'/FTB Library's real classes) so
 * that class is never touched - and its bytecode never verified/loaded by the JVM - when FTB
 * Chunks is absent.
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
}
