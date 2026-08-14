package com.sheyito.economicmaster.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.debt.DebtManager;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * "/debt" (self) and "/debt player <jugador>" are both public, mirroring /bal - anyone can
 * check their own or someone else's debt status.
 */
public final class DebtCommand {

    private DebtCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("debt")
                .executes(DebtCommand::self)
                .then(Commands.literal("player")
                        .then(Commands.argument("jugador", GameProfileArgument.gameProfile())
                                .executes(DebtCommand::other))));
    }

    private static int self(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        show(ctx, player.getUUID(), "Tu", ctx.getSource().getServer());
        return 1;
    }

    private static int other(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "jugador");
        GameProfile profile = profiles.iterator().next();
        show(ctx, profile.getId(), profile.getName() + " -", ctx.getSource().getServer());
        return 1;
    }

    private static void show(CommandContext<CommandSourceStack> ctx, UUID uuid, String label, MinecraftServer server) {
        DebtManager debtManager = DebtManager.get();
        double amount = debtManager.getDebtAmount(uuid);

        if (amount <= 0) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §f" + label + " deuda: §aninguna"), false);
            return;
        }

        Optional<Long> dueDay = debtManager.getDueGameDay(uuid);
        boolean overdue = debtManager.isOverdue(server, uuid);
        String estado = overdue ? "§c(VENCIDA)" : "§e(pendiente)";
        String plazo = dueDay.map(day -> "dia " + day).orElse("sin plazo registrado");

        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §f" + label + " deuda: §4"
                + Money.format(amount) + " §f- plazo: " + plazo + " " + estado), false);
    }
}
