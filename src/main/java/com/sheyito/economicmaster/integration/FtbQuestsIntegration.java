package com.sheyito.economicmaster.integration;

import com.sheyito.economicmaster.EconomicMaster;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.monopoly.MonopolyManager;
import com.sheyito.economicmaster.util.Money;
import com.sheyito.economicmaster.util.TransactionSounds;
import dev.architectury.event.EventResult;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Every FTB Quests type/package reference in the whole mod lives in this one class,
 * deliberately - {@link FTBQuestsCompat} only ever calls {@link #register()} from behind a
 * {@code ModList.get().isLoaded("ftbquests")} check, so this class's bytecode is only ever
 * verified/loaded by the JVM when FTB Quests (and its required Architectury API dependency)
 * are actually present. If FTB Quests is absent, this class is simply never touched, so there
 * is no {@link NoClassDefFoundError} risk despite compiling against FTB Quests' real classes
 * (a {@code compileOnly}, never {@code implementation}, dependency).
 */
final class FtbQuestsIntegration {

    private FtbQuestsIntegration() {
    }

    static void register() {
        ObjectCompletedEvent.QUEST.register(event -> {
            double amount = ConfigManager.questRewards().amount;
            if (MonopolyManager.get() != null) {
                amount = Money.round(amount * MonopolyManager.get().questRewardMultiplier());
            }
            EconomyManager economy = EconomyManager.get();
            if (economy == null || amount <= 0) {
                return EventResult.pass();
            }

            for (ServerPlayer player : event.getOnlineMembers()) {
                economy.giveEarned(player.getUUID(), amount);
                economy.trackName(player.getUUID(), player.getGameProfile().getName());
                player.sendSystemMessage(Component.literal("§6✦ §a[Recompensa de Mision] §f+" + Money.format(amount) + " §7añadidas a tu saldo."));
                TransactionSounds.success(player);
            }
            return EventResult.pass();
        });
        EconomicMaster.LOGGER.info("Sheyito's currency: integracion automatica con FTB Quests activada - cada mision completada otorga recompensa sin configuracion adicional.");
    }
}
