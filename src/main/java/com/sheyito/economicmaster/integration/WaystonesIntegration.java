package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.EconomicMaster;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.WaystoneTollConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.waystones.api.WaystoneTeleportContext;
import net.blay09.mods.waystones.api.event.WaystoneTeleportEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Every Waystones/Balm type reference in the whole mod lives in this one class, deliberately -
 * {@link WaystonesCompat} only ever calls {@link #register()} from behind a
 * {@code ModList.get().isLoaded("waystones")} check, so this class's bytecode is only ever
 * verified/loaded by the JVM when Waystones (and its required Balm dependency) are actually
 * present. The actual toll decision is delegated to {@link WaystoneTollLogic}, which is unit
 * tested on its own since it references no Waystones/Balm type.
 *
 * <p>Waystones' events go through Balm (its own cross-loader event bus), not NeoForge's - see
 * {@link Balm#getEvents()}. Only {@code Complete} is hooked: the toll is charged after a
 * successful teleport via {@link EconomyManager#charge}, which never blocks the teleport and can
 * leave the balance negative (see {@link WaystoneTollLogic}).
 */
final class WaystonesIntegration {

    private WaystonesIntegration() {
    }

    static void register() {
        Balm.getEvents().onEvent(WaystoneTeleportEvent.Complete.class, WaystonesIntegration::onComplete);
        EconomicMaster.LOGGER.info("Sheyito's currency: integracion con Waystones activada - usar un waystone cobra el peaje de movilidad configurado.");
    }

    private static void onComplete(WaystoneTeleportEvent.Complete event) {
        WaystoneTollConfig config = ConfigManager.waystoneToll();
        EconomyManager economy = EconomyManager.get();
        if (economy == null || !WaystoneTollLogic.isEnabled(config)) {
            return;
        }

        boolean succeeded = event.getPrimaryResult()
                .map(result -> result.isSuccessful())
                .orElse(false);
        if (!succeeded) {
            return;
        }

        ServerPlayer player = resolvePlayer(event.getContext());
        if (player == null) {
            return;
        }

        WaystoneTollLogic.applyToll(economy, config, player.getUUID());
        player.sendSystemMessage(Component.literal("§6[Sheyito's currency] §f-" + Money.format(config.cost) + " Sheyicoins (peaje de movilidad)."));
    }

    private static ServerPlayer resolvePlayer(WaystoneTeleportContext context) {
        Entity entity = context.getEntity();
        return entity instanceof ServerPlayer player ? player : null;
    }
}
