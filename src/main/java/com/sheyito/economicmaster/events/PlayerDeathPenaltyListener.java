package com.sheyito.economicmaster.events;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.DebtConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.UUID;

/**
 * Applies a death penalty to the player: loses {@link DebtConfig#penaltyPercent} of their
 * current balance via {@link EconomyManager#take(UUID, double)}, which guarantees the balance
 * never goes negative.
 *
 * <p>Mirrors {@link MobKillListener}'s client-side/config-null guards - see that class for why
 * they're needed even though this mod is server-only.
 */
public class PlayerDeathPenaltyListener {

    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) {
            return;
        }

        DebtConfig config = ConfigManager.debt();
        if (config == null || !config.enabled || EconomyManager.get() == null) {
            return;
        }

        if (!(victim instanceof ServerPlayer player)) {
            return;
        }

        applyDeathPenalty(EconomyManager.get(), config, player.getServer(), player);
    }

    /**
     * Extracted from {@link #onLivingDeath} so it can be exercised in a unit test without
     * needing a real {@code LivingDeathEvent}, mirroring the plain-value-in/out style the rest
     * of this mod's managers use.
     */
    static void applyDeathPenalty(EconomyManager economy, DebtConfig config,
                                   MinecraftServer server, ServerPlayer player) {
        UUID uuid = player.getUUID();
        double balance = economy.getBalance(uuid);
        double penalty = balance * config.penaltyPercent;

        economy.take(uuid, penalty);
        player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fMoriste y perdiste "
                + "§4-" + Money.format(penalty) + " §f(" + (int) (config.penaltyPercent * 100) + "% de tu patrimonio)."));
    }
}
