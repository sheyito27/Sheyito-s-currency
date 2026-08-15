package com.sheyito.economicmaster.integration;

import com.mojang.datafixers.util.Either;
import com.sheyito.economicmaster.EconomicMaster;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.WaystoneTollConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.waystones.api.WaystoneTeleportContext;
import net.blay09.mods.waystones.api.error.WaystoneTeleportError;
import net.blay09.mods.waystones.api.event.WaystoneTeleportEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.concurrent.CompletableFuture;

/**
 * Every Waystones/Balm type reference in the whole mod lives in this one class, deliberately -
 * {@link WaystonesCompat} only ever calls {@link #register()} from behind a
 * {@code ModList.get().isLoaded("waystones")} check, so this class's bytecode is only ever
 * verified/loaded by the JVM when Waystones (and its required Balm dependency) are actually
 * present. The actual toll decision is delegated to {@link WaystoneTollLogic}, which is unit
 * tested on its own since it references no Waystones/Balm type.
 *
 * <p>Waystones' events go through Balm (its own cross-loader event bus), not NeoForge's - see
 * {@link Balm#getEvents()}. Two phases are hooked: {@code Prepare} blocks the teleport before it
 * happens if the player can't afford the toll (via its documented async preparation-task
 * mechanism - the only way to fail a teleport without touching Waystones' undocumented
 * {@code WarpRequirement} composition, which risks clobbering the vanilla XP-cost requirement);
 * {@code Complete} then actually charges the toll, but only once the teleport is confirmed
 * successful, so a player who could afford it at {@code Prepare} time is never charged for a
 * teleport that ultimately failed for an unrelated reason.
 */
final class WaystonesIntegration {

    private WaystonesIntegration() {
    }

    static void register() {
        Balm.getEvents().onEvent(WaystoneTeleportEvent.Prepare.class, WaystonesIntegration::onPrepare);
        Balm.getEvents().onEvent(WaystoneTeleportEvent.Complete.class, WaystonesIntegration::onComplete);
        EconomicMaster.LOGGER.info("Sheyito's currency: integracion con Waystones activada - usar un waystone cobra el peaje de movilidad configurado.");
    }

    /** Phase 1: check only, never deducts. Blocks the teleport if the player can't afford it. */
    private static void onPrepare(WaystoneTeleportEvent.Prepare event) {
        event.addPreparationTask(prior -> {
            if (prior.right().isPresent()) {
                // a prior preparation task already failed for an unrelated reason - pass it
                // through unchanged, don't overwrite it
                return CompletableFuture.completedFuture(prior);
            }

            WaystoneTollConfig config = ConfigManager.waystoneToll();
            EconomyManager economy = EconomyManager.get();
            ServerPlayer player = resolvePlayer(event.getContext());

            if (economy == null || player == null || WaystoneTollLogic.canAfford(economy, config, player.getUUID())) {
                return CompletableFuture.completedFuture(Either.left(null));
            }

            Component message = Component.literal("§c[Sheyito's currency] §fNo tienes suficiente saldo para usar este waystone (cuesta "
                    + Money.format(config.cost) + " Sheyicoins).");
            return CompletableFuture.completedFuture(Either.right(new WaystoneTeleportError(message)));
        });
    }

    /** Phase 2: charges the toll, only if the primary teleport actually succeeded. */
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

        if (WaystoneTollLogic.chargeToll(economy, config, player.getUUID())) {
            player.sendSystemMessage(Component.literal("§6[Sheyito's currency] §f-" + Money.format(config.cost) + " Sheyicoins (peaje de movilidad)."));
        }
    }

    private static ServerPlayer resolvePlayer(WaystoneTeleportContext context) {
        Entity entity = context.getEntity();
        return entity instanceof ServerPlayer player ? player : null;
    }
}
