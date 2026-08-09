package com.sheyito.economicmaster.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.SalaryConfig;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.LevelCurve;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.UUID;

/**
 * "/bal" (self), "/bal player <jugador>" and "/bal level [jugador]" are all public - no OP
 * needed to check balances or leveling progress, your own or anyone else's.
 */
public final class BalCommand {

    private BalCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bal")
                .executes(BalCommand::self)
                .then(Commands.literal("player")
                        .then(Commands.argument("jugador", GameProfileArgument.gameProfile())
                                .executes(BalCommand::other)))
                .then(Commands.literal("level")
                        .executes(BalCommand::levelSelf)
                        .then(Commands.argument("jugador", GameProfileArgument.gameProfile())
                                .executes(BalCommand::levelOther))));
    }

    private static int self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        double balance = EconomyManager.get().getBalance(player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fTu saldo: §e" + Money.format(balance)), false);
        return 1;
    }

    private static int other(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "jugador");
        GameProfile profile = profiles.iterator().next();
        UUID uuid = profile.getId();
        double balance = EconomyManager.get().getBalance(uuid);
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fSaldo de " + profile.getName() + ": §e" + Money.format(balance)), false);
        return 1;
    }

    private static int levelSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        showLevel(ctx, player.getUUID(), player.getGameProfile().getName());
        return 1;
    }

    private static int levelOther(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "jugador");
        GameProfile profile = profiles.iterator().next();
        showLevel(ctx, profile.getId(), profile.getName());
        return 1;
    }

    private static void showLevel(CommandContext<CommandSourceStack> ctx, UUID uuid, String name) {
        SalaryConfig config = ConfigManager.salary();
        double xp = EconomyManager.get().getXp(uuid);
        int level = LevelCurve.levelForXp(xp, config.maxLevel, config.levelCurveBaseXp);
        double xpIntoLevel = xp - LevelCurve.cumulativeXpForLevel(level, config.levelCurveBaseXp);
        double currentSalary = LevelCurve.salaryForLevel(level, config.baseSalary, config.maxSalary, config.maxLevel);

        if (level >= config.maxLevel) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §f" + name + " - Nivel §6" + level + " §f(MAXIMO) - salario diario: §e" + Money.format(currentSalary)), false);
            return;
        }

        double xpForNext = LevelCurve.xpForLevel(level + 1, config.levelCurveBaseXp);
        double progressPercent = Math.min(100.0, (xpIntoLevel / xpForNext) * 100.0);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "§a[Sheyito's currency] §f%s - Nivel §e%d §f- XP: §b%.1f / %.1f §f(%.1f%%) - salario diario: §e%s",
                name, level, xpIntoLevel, xpForNext, progressPercent, Money.format(currentSalary))), false);
    }
}
