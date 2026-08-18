package com.sheyito.economicmaster.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.chunk.ChunkClaimRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * Admin-only testing tool for the renta de chunks feature (see {@code ChunkClaimRegistry}):
 * drops a player's live claim count back to 0 so the `n^1.5` pricing curve can be re-tested from
 * the start without unclaiming every chunk one by one. Does not refund anything - see
 * {@link ChunkClaimRegistry#resetClaimCount}.
 *
 * <p>Lives under {@code /sc}, the shared root for every admin/dev command in this mod (see also
 * {@code EconomicMasterCommand}, {@code DimensionCommand}) - Brigadier merges multiple
 * {@code register(Commands.literal("sc")...)} calls from different classes into one command
 * tree, so each class can keep owning its own subcommand independently.
 */
public final class ChunkCommand {

    private ChunkCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sc")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("chunk")
                        .then(Commands.literal("reset")
                                .then(Commands.argument("jugador", GameProfileArgument.gameProfile())
                                        .executes(ChunkCommand::reset)))));
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "jugador");
        GameProfile target = profiles.iterator().next();
        UUID uuid = target.getId();

        ChunkClaimRegistry.get().resetClaimCount(uuid);

        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §f" + target.getName()
                + " ya tiene su recuento de chunks a 0."), true);
        return 1;
    }
}
