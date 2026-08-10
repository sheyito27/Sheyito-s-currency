package com.sheyito.economicmaster.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.trade.TradeManager;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * "/trade <jugador> [dinero] [mensaje]" sends an invite; the target must explicitly
 * "/trade accept" before any GUI opens, so no one gets an unsolicited trade window. Any money
 * offered is fixed right here at invite time (shown to the target before they accept) and flows
 * automatically from the inviter to whoever accepts once the trade completes - it is not
 * negotiable inside the GUI, only the item-offer rows are.
 */
public final class TradeCommand {

    private TradeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("trade")
                .then(Commands.literal("accept").executes(TradeCommand::accept))
                .then(Commands.literal("deny").executes(TradeCommand::deny))
                .then(Commands.literal("cancel").executes(TradeCommand::cancel))
                .then(Commands.argument("jugador", EntityArgument.player())
                        .executes(ctx -> invite(ctx, 0, ""))
                        .then(Commands.argument("dinero", DoubleArgumentType.doubleArg(0))
                                .executes(ctx -> invite(ctx, DoubleArgumentType.getDouble(ctx, "dinero"), ""))
                                .then(Commands.argument("mensaje", StringArgumentType.greedyString())
                                        .executes(ctx -> invite(ctx, DoubleArgumentType.getDouble(ctx, "dinero"), StringArgumentType.getString(ctx, "mensaje")))))));
    }

    private static int invite(CommandContext<CommandSourceStack> ctx, double money, String message) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "jugador");
        TradeManager manager = TradeManager.get();

        if (target.getUUID().equals(sender.getUUID())) {
            ctx.getSource().sendFailure(Component.literal("§cNo puedes intercambiar contigo mismo."));
            return 0;
        }
        if (manager.isBusy(sender.getUUID())) {
            ctx.getSource().sendFailure(Component.literal("§cYa estás en un intercambio o tienes una invitación pendiente."));
            return 0;
        }
        if (manager.isBusy(target.getUUID())) {
            ctx.getSource().sendFailure(Component.literal("§c" + target.getGameProfile().getName() + " ya está ocupado con otro intercambio."));
            return 0;
        }
        if (money > 0 && EconomyManager.get().getBalance(sender.getUUID()) < money) {
            ctx.getSource().sendFailure(Component.literal("§cNo tienes saldo suficiente para ofrecer " + Money.format(money) + "."));
            return 0;
        }

        manager.invite(sender, target, money, message);
        return 1;
    }

    private static int accept(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!TradeManager.get().accept(player)) {
            ctx.getSource().sendFailure(Component.literal("§cNo tienes ninguna invitación de intercambio pendiente."));
            return 0;
        }
        return 1;
    }

    private static int deny(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!TradeManager.get().deny(player)) {
            ctx.getSource().sendFailure(Component.literal("§cNo tienes ninguna invitación de intercambio pendiente."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fInvitacion rechazada."), false);
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        TradeManager.get().cancel(player.getUUID(), "cancelado por " + player.getGameProfile().getName());
        return 1;
    }
}
