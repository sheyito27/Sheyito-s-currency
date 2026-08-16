package com.sheyito.economicmaster.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.auction.AuctionPoolManager;
import com.sheyito.economicmaster.embargo.EmbargoManager;
import com.sheyito.economicmaster.embargo.EmbargoVoteMenu;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Two separate roots, on purpose: "embargo vote" is player-facing (anyone eligible can cast a
 * secret ballot on which of their own seized items - or someone else's - goes to auction), so it
 * does NOT live under the admin-only {@code /sc} root. "sc embargo retirar" and "sc embargo
 * cerrar" ARE admin tools, so they do.
 */
public final class EmbargoCommand {

    private EmbargoCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("embargo")
                .then(Commands.literal("vote")
                        .executes(EmbargoCommand::vote)));

        dispatcher.register(Commands.literal("sc")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("embargo")
                        .then(Commands.literal("retirar")
                                .executes(EmbargoCommand::retirar))
                        .then(Commands.literal("cerrar")
                                .then(Commands.argument("jugador", GameProfileArgument.gameProfile())
                                        .executes(EmbargoCommand::cerrar)))));
    }

    private static int vote(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        EmbargoManager manager = EmbargoManager.get();
        Optional<Long> voteId = manager == null ? Optional.empty() : manager.openVoteFor(player.getUUID());
        if (voteId.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cNo hay ninguna votacion de embargo activa en la que puedas participar."));
            return 0;
        }

        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new EmbargoVoteMenu(id, inv, voteId.get(), player.getUUID()),
                Component.literal("Votacion de embargo")));
        return 1;
    }

    private static int retirar(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer admin = ctx.getSource().getPlayerOrException();
        Optional<AuctionPoolManager.PooledItem> next = AuctionPoolManager.get().retrieveNext();
        if (next.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§cLa pool de subastas esta vacia."));
            return 0;
        }

        AuctionPoolManager.PooledItem pooled = next.get();
        admin.getInventory().placeItemBackInInventory(pooled.stack());
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fRetiraste de la pool: "
                + pooled.stack().getHoverName().getString() + " x" + pooled.stack().getCount()
                + " (incautado a " + pooled.seizedFromName() + ")."), true);
        return 1;
    }

    private static int cerrar(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "jugador");
        GameProfile target = profiles.iterator().next();
        UUID uuid = target.getId();

        EmbargoManager manager = EmbargoManager.get();
        boolean closed = manager != null && manager.forceCloseOldestVote(uuid, ctx.getSource().getServer());
        if (!closed) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cNo hay ninguna votacion de embargo activa para " + target.getName() + "."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fForzado el cierre de la "
                + "votacion de embargo mas antigua de " + target.getName() + "."), true);
        return 1;
    }
}
