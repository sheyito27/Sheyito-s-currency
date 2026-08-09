package com.sheyito.economicmaster.events;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.MobRewardsConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Awards money for killing whitelisted entities, driven entirely by
 * config/sheyitoscurrency/mobs.json. Fires only on the logical server (LivingDeathEvent
 * is a server-only event), so this mod needs no isClientSide checks here.
 */
public class MobKillListener {

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        MobRewardsConfig config = ConfigManager.mobRewards();
        if (!config.enabled || EconomyManager.get() == null) {
            return;
        }

        LivingEntity victim = event.getEntity();
        if (victim instanceof ServerPlayer) {
            return;
        }

        ServerPlayer killer = resolveKiller(event, config);
        if (killer == null) {
            return;
        }

        String entityId = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
        Double reward = config.rewards.get(entityId);
        if (reward == null || reward <= 0) {
            return;
        }

        EconomyManager.get().giveEarned(killer.getUUID(), reward);
        EconomyManager.get().trackName(killer.getUUID(), killer.getGameProfile().getName());

        if (ConfigManager.general().broadcastKillRewards) {
            killer.sendSystemMessage(Component.literal("§a[Sheyito's currency] §f+" + Money.format(reward) + " por eliminar " + victim.getName().getString() + "."));
        }
    }

    /**
     * When {@code requireDirectPlayerKill} is true, only credits kills where the player
     * was themselves the damage source (melee, bow, tridents, etc). When false, also
     * credits the owner of a tamed pet (wolf, cat) that lands the killing blow.
     */
    private static ServerPlayer resolveKiller(LivingDeathEvent event, MobRewardsConfig config) {
        Entity direct = event.getSource().getEntity();
        if (direct instanceof ServerPlayer player) {
            return player;
        }
        if (!config.requireDirectPlayerKill && direct instanceof TamableAnimal pet
                && pet.getOwner() instanceof ServerPlayer owner) {
            return owner;
        }
        return null;
    }
}
