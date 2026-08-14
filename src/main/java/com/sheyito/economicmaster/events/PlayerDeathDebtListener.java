package com.sheyito.economicmaster.events;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.DebtConfig;
import com.sheyito.economicmaster.debt.DebtManager;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.GameTime;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import java.util.UUID;

/**
 * Charges a player a death penalty. Below {@link DebtConfig#balanceThreshold}, it's a flat
 * {@link DebtConfig#penaltyAmount} taken via the unclamped {@link EconomyManager#charge}, which
 * can push the balance negative - that's the debt, tracked in {@link DebtManager} with a
 * {@link DebtConfig#deadlineGameDays}-day repayment window. Above the threshold, it's
 * {@link DebtConfig#penaltyPercent} of the current balance via the normal, fund-checked
 * {@link EconomyManager#take}, which can never go negative.
 *
 * <p>Mirrors {@link MobKillListener}'s client-side/config-null guards - see that class for why
 * they're needed even though this mod is server-only.
 */
public class PlayerDeathDebtListener {

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

        applyDeathPenalty(EconomyManager.get(), DebtManager.get(), config, player.getServer(), player);
    }

    /**
     * Extracted from {@link #onLivingDeath} so it can be exercised in a unit test without
     * needing a real {@code LivingDeathEvent}, mirroring the plain-value-in/out style the rest
     * of this mod's managers use.
     */
    static void applyDeathPenalty(EconomyManager economy, DebtManager debtManager, DebtConfig config,
                                   MinecraftServer server, ServerPlayer player) {
        UUID uuid = player.getUUID();
        double balance = economy.getBalance(uuid);

        if (balance <= config.balanceThreshold) {
            economy.charge(uuid, config.penaltyAmount);
            double newBalance = economy.getBalance(uuid);

            if (newBalance < 0) {
                long dueDay = GameTime.currentDay(server) + config.deadlineGameDays;
                debtManager.incurDebt(uuid, dueDay);

                if (config.broadcastOnDebtIncurred) {
                    player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fMoriste con poco dinero: "
                            + "§4-" + Money.format(config.penaltyAmount) + " §f. Quedas en deuda de §4"
                            + Money.format(-newBalance) + " §f, con plazo de " + config.deadlineGameDays + " dia(s) para saldarla."));
                }
            }
        } else {
            double penalty = balance * config.penaltyPercent;
            economy.take(uuid, penalty);
            player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fMoriste y perdiste "
                    + "§4-" + Money.format(penalty) + " §f(" + (int) (config.penaltyPercent * 100) + "% de tu patrimonio)."));
        }
    }
}
