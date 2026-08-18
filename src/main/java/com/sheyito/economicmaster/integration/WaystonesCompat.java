package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.EconomicMaster;
import net.neoforged.fml.ModList;

/**
 * Pure soft-dependency gate: this class itself never references any Waystones or Balm type, so
 * it is always safe to load. When Waystones is present, it delegates to
 * {@link WaystonesIntegration} (which does reference Waystones'/Balm's real classes) so that
 * class is never touched - and its bytecode never verified/loaded by the JVM - when Waystones
 * is absent. Balm doesn't need its own check here: Waystones hard-requires it, so if Waystones
 * is loaded, Balm is guaranteed to be loaded too.
 */
public final class WaystonesCompat {

    private static final String WAYSTONES_MODID = "waystones";

    private WaystonesCompat() {
    }

    public static void logDetection() {
        if (ModList.get().isLoaded(WAYSTONES_MODID)) {
            WaystonesIntegration.register();
        } else {
            EconomicMaster.LOGGER.info("Waystones no detectado: Sheyito's currency funciona de forma independiente (sin peaje de movilidad).");
        }
    }
}
