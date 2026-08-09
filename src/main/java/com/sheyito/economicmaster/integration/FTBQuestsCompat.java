package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.EconomicMaster;
import net.neoforged.fml.ModList;

/**
 * Pure soft-dependency detection: Sheyito's currency never compiles or links against FTB Quests.
 * All reward handoff happens through FTB Quests' own "Command" reward type invoking
 * "/{modid} reward <jugador>", so nothing here is required for
 * the integration to work - this only logs whether FTB Quests is present for diagnostics.
 */
public final class FTBQuestsCompat {

    private static final String FTBQUESTS_MODID = "ftbquests";

    private FTBQuestsCompat() {
    }

    public static void logDetection() {
        if (ModList.get().isLoaded(FTBQUESTS_MODID)) {
            EconomicMaster.LOGGER.info("FTB Quests detectado: usa '/{} reward <jugador>' como recompensa de tipo Comando en tus misiones.", EconomicMaster.MODID);
        } else {
            EconomicMaster.LOGGER.info("FTB Quests no detectado: Sheyito's currency funciona de forma independiente. El comando 'reward' sigue disponible para otros usos.");
        }
    }
}
