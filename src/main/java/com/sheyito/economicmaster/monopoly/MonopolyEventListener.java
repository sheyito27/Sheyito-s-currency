package com.sheyito.economicmaster.monopoly;

import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import com.sheyito.economicmaster.util.TransactionSounds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Otorga la recompensa extra del evento MOB_WANTED cuando un jugador mata directamente al mob
 * que el evento eligió. Es independiente de la caza de mobs de mobs.json: el bounty se paga
 * aunque la caza esté desactivada, y se suma a cualquier recompensa normal que sí se pague.
 */
public class MonopolyEventListener {

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) {
            return;
        }
        if (EconomyManager.get() == null || MonopolyManager.get() == null || !MonopolyManager.get().isMobWanted()) {
            return;
        }

        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
        if (!entityId.equals(MonopolyManager.get().wantedMob())) {
            return;
        }

        if (event.getSource().getEntity() instanceof ServerPlayer killer) {
            double bounty = MonopolyManager.get().wantedBounty();
            EconomyManager.get().giveEarned(killer.getUUID(), bounty);
            EconomyManager.get().trackName(killer.getUUID(), killer.getGameProfile().getName());
            killer.sendSystemMessage(Component.literal("§6[Monopoly] §f+" + Money.format(bounty) + " por eliminar el mob buscado (" + victim.getName().getString() + ")."));
            TransactionSounds.success(killer);
        }
    }
}
