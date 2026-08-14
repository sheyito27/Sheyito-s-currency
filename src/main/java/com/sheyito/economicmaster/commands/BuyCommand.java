package com.sheyito.economicmaster.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import com.sheyito.economicmaster.util.TransactionSounds;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * "/buy xp <cantidad>" trades Sheyicoins for vanilla Minecraft experience points (the enchanting
 * XP bar) at {@code ConfigManager.xpShop().coinsPerXpPoint} SC per point - unrelated to the mod's
 * own salary level/XP (see {@code SalaryConfig}, {@code LevelCurve}): this never calls
 * {@code giveEarned}, only {@code take()} plus vanilla's {@code Player#giveExperiencePoints}.
 * "buy" is kept as the base literal (not "xp buy") so future purchasable things can live under
 * "/buy <otra-cosa>" without restructuring commands.
 */
public final class BuyCommand {

    private BuyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("buy")
                .then(Commands.literal("xp")
                        .then(Commands.argument("cantidad", IntegerArgumentType.integer(1))
                                .executes(BuyCommand::buyXp))));
    }

    private static int buyXp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int points = IntegerArgumentType.getInteger(ctx, "cantidad");
        double cost = Money.round(points * ConfigManager.xpShop().coinsPerXpPoint);

        if (!EconomyManager.get().take(player.getUUID(), cost)) {
            ctx.getSource().sendFailure(Component.literal("§cNo tienes saldo suficiente para comprar " + points + " XP (cuesta " + Money.format(cost) + ")."));
            TransactionSounds.failure(player);
            return 0;
        }

        player.giveExperiencePoints(points);
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fCompraste " + points + " XP por " + Money.format(cost) + "."), false);
        TransactionSounds.success(player);
        return 1;
    }
}
