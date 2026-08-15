package com.sheyito.economicmaster.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.dimension.DimensionUnlockManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.UUID;

/**
 * Admin-only testing tool for {@link com.sheyito.economicmaster.events.DimensionUnlockListener}:
 * resets a player's unlock state for a dimension so the paywall can be re-triggered without a
 * fresh world. Does not refund the price they paid - see {@link DimensionUnlockManager#lock}.
 */
public final class DimensionCommand {

    private DimensionCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dimension")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("lock")
                        .then(Commands.argument("jugador", GameProfileArgument.gameProfile())
                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                        .executes(DimensionCommand::lock)))));
    }

    private static int lock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "jugador");
        GameProfile target = profiles.iterator().next();
        UUID uuid = target.getId();

        ServerLevel level = DimensionArgument.getDimension(ctx, "dimension");
        ResourceKey<Level> dimension = level.dimension();

        DimensionUnlockManager.get().lock(uuid, dimension);

        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §f" + target.getName()
                + " ya no tiene desbloqueada esa dimension."), true);
        return 1;
    }
}
