package com.sheyito.economicmaster.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.integration.FTBChunksCompat;
import com.sheyito.economicmaster.rent.RentManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;

import java.util.Collection;
import java.util.UUID;

/**
 * Admin-only testing tool for both renta mechanics ({@code rent.RentManager}'s progressive
 * profit tax and {@code integration.FtbChunksIntegration}'s force-load rent): forces an
 * immediate billing pass for one player, ignoring whether {@code intervalGameDays} has actually
 * elapsed - saves waiting a real 7 game days to see a charge fire. No-op for whichever half
 * doesn't apply (no gains ever tracked for the profit tax, or no force-loaded chunks - nothing
 * to bill on that side either way).
 *
 * <p>Lives under {@code /sc}, the shared root for every admin/dev command in this mod - Brigadier
 * merges multiple {@code register(Commands.literal("sc")...)} calls from different classes into
 * one command tree, so this class doesn't need to know about any other command.
 */
public final class RentCommand {

    private RentCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sc")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("rent")
                        .then(Commands.literal("force")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes(RentCommand::force)))));
    }

    private static int force(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        GameProfile target = profiles.iterator().next();
        UUID uuid = target.getId();

        RentManager.get().forceProcess(ctx.getSource().getServer(), uuid);
        FTBChunksCompat.forceProcessForceLoadRent(ctx.getSource().getServer(), uuid);

        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fForzada la renta de "
                + target.getName() + " (ganancias + force-load de chunks)."), true);
        return 1;
    }
}
