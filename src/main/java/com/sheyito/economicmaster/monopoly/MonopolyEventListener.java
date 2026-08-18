package com.sheyito.economicmaster.monopoly;

import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import com.sheyito.economicmaster.util.TransactionSounds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.Map;
import java.util.UUID;

/**
 * Reparte la recompensa extra del evento MOB_WANTED entre todos los jugadores que dañaron al mob
 * que el evento eligió: cada golpe se registra en {@link LivingDamageEvent.Pre} y, al morir el mob,
 * el bounty se reparte por igual entre sus contribuidores (la fracción que no divida exacta no se
 * acuña). Es independiente de la caza de mobs de mobs.json: el bounty se paga aunque la caza esté
 * desactivada, y se suma a cualquier recompensa normal que sí se pague.
 */
public class MonopolyEventListener {

    @SubscribeEvent
    public void onLivingDamagePre(LivingDamageEvent.Pre event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) {
            return;
        }
        MonopolyManager monopoly = MonopolyManager.get();
        if (EconomyManager.get() == null || monopoly == null || !monopoly.mobWantedPayoutActive()) {
            return;
        }
        if (event.getNewDamage() <= 0f) {
            return;
        }
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
        if (!entityId.equals(monopoly.wantedMob())) {
            return;
        }
        if (event.getSource().getEntity() instanceof ServerPlayer attacker) {
            long tick = victim.level() instanceof ServerLevel level ? level.getGameTime() : 0L;
            monopoly.recordDamage(victim.getUUID(), attacker.getUUID(), attacker.getGameProfile().getName(), tick);
        }
    }

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) {
            return;
        }
        MonopolyManager monopoly = MonopolyManager.get();
        if (EconomyManager.get() == null || monopoly == null || !monopoly.mobWantedPayoutActive()) {
            return;
        }
        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
        if (!entityId.equals(monopoly.wantedMob())) {
            return;
        }

        UUID victimId = victim.getUUID();
        Map<UUID, String> contributors = monopoly.contributorNames(victimId);
        monopoly.forgetContributors(victimId);
        if (contributors.isEmpty()) {
            return;
        }

        double share = MonopolyManager.bountyShare(monopoly.wantedBounty(), contributors.size());
        if (share <= 0) {
            return;
        }

        String mobName = victim.getName().getString();
        ServerLevel level = (ServerLevel) victim.level();
        EconomyManager economy = EconomyManager.get();
        for (Map.Entry<UUID, String> contributor : contributors.entrySet()) {
            economy.giveEarned(contributor.getKey(), share);
            economy.trackName(contributor.getKey(), contributor.getValue());
            ServerPlayer target = level.getServer().getPlayerList().getPlayer(contributor.getKey());
            if (target != null) {
                target.sendSystemMessage(Component.literal("§6[Monopoly] §f+" + Money.format(share) + " por eliminar el mob buscado (" + mobName + ")."));
                TransactionSounds.success(target);
            }
        }
        monopoly.onMobWantedKilled(level.getServer());
    }
}
