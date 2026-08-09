package com.sheyito.economicmaster.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.trade.TradeManager;
import com.sheyito.economicmaster.trade.TradeSession;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * "/trade <jugador>" sends an invite; the target must explicitly "/trade accept" before any
 * GUI opens, so no one gets an unsolicited trade window. Once inside a trade, "/trade money
 * <monto>" is the only way to offer currency (a vanilla chest-style menu has no text field).
 */
public final class TradeCommand {

    private TradeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("trade")
                .then(Commands.literal("accept").executes(TradeCommand::accept))
                .then(Commands.literal("deny").executes(TradeCommand::deny))
                .then(Commands.literal("cancel").executes(TradeCommand::cancel))
                .then(Commands.literal("money")
                        .then(Commands.argument("monto", DoubleArgumentType.doubleArg(0))
                                .executes(TradeCommand::money)))
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

    private static int money(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        double monto = DoubleArgumentType.getDouble(ctx, "monto");

        TradeSession session = TradeManager.get().getSession(player.getUUID());
        if (session == null) {
            ctx.getSource().sendFailure(Component.literal("§cNo estás en ningún intercambio activo."));
            return 0;
        }
        if (!session.setMoneyOffer(player.getUUID(), monto)) {
            ctx.getSource().sendFailure(Component.literal("§cNo se pudo actualizar el monto - revisa tu saldo, o el intercambio ya está bloqueado esperando confirmación."));
            return 0;
        }

        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fAhora ofreces " + Money.format(monto) + " en el intercambio."), false);
        return 1;
    }
}
