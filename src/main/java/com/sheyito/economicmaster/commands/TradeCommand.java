package com.sheyito.economicmaster.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.trade.TradeManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * "/trade <jugador>" sends an invite; the target must explicitly "/trade accept" before any
 * GUI opens, so no one gets an unsolicited trade window. Once inside a trade, currency is
 * offered by physically depositing ingots/gems into the dedicated slots in the GUI itself
 * (see {@link com.sheyito.economicmaster.trade.TradeSession#CURRENCY_DENOMINATIONS}) - there
 * is no money-related sub-command.
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
                        .executes(TradeCommand::invite)));
    }

    private static int invite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
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

        manager.invite(sender, target);
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
