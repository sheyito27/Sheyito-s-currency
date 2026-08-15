package com.sheyito.economicmaster.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.UUID;

public final class EcoCommand {

    private EcoCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("eco")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("give")
                        .then(Commands.argument("jugador", GameProfileArgument.gameProfile())
                                .then(Commands.argument("cantidad", DoubleArgumentType.doubleArg(0.01))
                                        .executes(ctx -> apply(ctx, Operation.GIVE)))))
                .then(Commands.literal("take")
                        .then(Commands.argument("jugador", GameProfileArgument.gameProfile())
                                .then(Commands.argument("cantidad", DoubleArgumentType.doubleArg(0.01))
                                        .executes(ctx -> apply(ctx, Operation.TAKE)))))
                .then(Commands.literal("set")
                        .then(Commands.argument("jugador", GameProfileArgument.gameProfile())
                                .then(Commands.argument("cantidad", DoubleArgumentType.doubleArg(0))
                                        .executes(ctx -> apply(ctx, Operation.SET)))))
                .then(Commands.literal("charge")
                        .then(Commands.argument("jugador", GameProfileArgument.gameProfile())
                                .then(Commands.argument("cantidad", DoubleArgumentType.doubleArg(0.01))
                                        .executes(ctx -> apply(ctx, Operation.CHARGE)))))
                .then(Commands.literal("reload").executes(EcoCommand::reload)));
    }

    /**
     * {@code CHARGE} is the only admin-facing operation that can leave a player's balance
     * negative - the same {@code EconomyManager.charge} used by the waystone toll (see
     * {@code integration.WaystoneTollLogic}). Useful to manually reproduce/test that without
     * needing Waystones installed.
     */
    private enum Operation { GIVE, TAKE, SET, CHARGE }

    private static int apply(CommandContext<CommandSourceStack> ctx, Operation op) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "jugador");
        GameProfile target = profiles.iterator().next();
        UUID uuid = target.getId();
        double amount = DoubleArgumentType.getDouble(ctx, "cantidad");
        EconomyManager economy = EconomyManager.get();
        economy.trackName(uuid, target.getName());

        switch (op) {
            case GIVE -> economy.give(uuid, amount);
            case SET -> economy.setBalance(uuid, amount);
            case CHARGE -> economy.charge(uuid, amount);
            case TAKE -> {
                if (!economy.take(uuid, amount)) {
                    ctx.getSource().sendFailure(Component.literal("§c" + target.getName() + " no tiene saldo suficiente."));
                    return 0;
                }
            }
        }

        double newBalance = economy.getBalance(uuid);
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fSaldo de " + target.getName() + " actualizado a " + Money.format(newBalance) + "."), true);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ConfigManager.load();
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fConfiguracion recargada."), true);
        return 1;
    }
}
