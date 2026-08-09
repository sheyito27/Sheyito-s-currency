package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.EconomicMaster;
import net.neoforged.fml.ModList;

/**
 * Pure soft-dependency gate: this class itself never references any FTB Quests type, so it is
 * always safe to load. When FTB Quests is present, it delegates to {@link FtbQuestsIntegration}
 * (which does reference FTB Quests' real classes) so that class is never touched - and its
 * bytecode never verified/loaded by the JVM - when FTB Quests is absent.
 * "/{modid} reward <jugador>" (see EconomicMasterCommand) still works standalone either way,
 * for server owners who want to hand out a bonus on top of, or instead of, the automatic reward.
 */
public final class FTBQuestsCompat {

    private static final String FTBQUESTS_MODID = "ftbquests";

    private FTBQuestsCompat() {
    }

    public static void logDetection() {
        if (ModList.get().isLoaded(FTBQUESTS_MODID)) {
            FtbQuestsIntegration.register();
        } else {
            EconomicMaster.LOGGER.info("FTB Quests no detectado: Sheyito's currency funciona de forma independiente. El comando 'reward' sigue disponible para otros usos.");
        }
    }
}
