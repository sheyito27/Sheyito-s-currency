package com.sheyito.economicmaster.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import com.sheyito.economicmaster.util.TransactionSounds;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.UUID;

public final class PayCommand {

    private PayCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("pay")
                .then(Commands.argument("jugador", GameProfileArgument.gameProfile())
                        .then(Commands.argument("cantidad", DoubleArgumentType.doubleArg(0.01))
                                .executes(PayCommand::run))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer sender = ctx.getSource().getPlayerOrException();
        double amount = DoubleArgumentType.getDouble(ctx, "cantidad");
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "jugador");
        GameProfile target = profiles.iterator().next();
        UUID targetUuid = target.getId();

        if (targetUuid.equals(sender.getUUID())) {
            ctx.getSource().sendFailure(Component.literal("§cNo puedes pagarte a ti mismo."));
            return 0;
        }

        double grossFromSender = EconomyManager.get().grossWithTax(amount);
        if (!EconomyManager.get().pay(sender.getUUID(), targetUuid, amount)) {
            ctx.getSource().sendFailure(Component.literal("§cNo tienes saldo suficiente para pagar " + Money.format(grossFromSender) + " (con IVA)."));
            TransactionSounds.failure(sender);
            return 0;
        }

        double netToTarget = EconomyManager.get().netAfterTax(amount);
        EconomyManager.get().trackName(targetUuid, target.getName());
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fLe pagaste " + Money.format(grossFromSender) + " a " + target.getName() + " (recibe " + Money.format(netToTarget) + ", IVA incluido)."), false);
        TransactionSounds.success(sender);

        ServerPlayer targetPlayer = ctx.getSource().getServer().getPlayerList().getPlayer(targetUuid);
        if (targetPlayer != null) {
            targetPlayer.sendSystemMessage(Component.literal("§a[Sheyito's currency] §fHas recibido " + Money.format(netToTarget) + " de " + sender.getGameProfile().getName() + " (IVA incluido)."));
            TransactionSounds.success(targetPlayer);
        }
        return 1;
    }
}
