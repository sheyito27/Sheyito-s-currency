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
 * Two separate roots, on purpose: "liquidation vote" is player-facing (anyone eligible can cast a
 * secret ballot on which of their own seized items - or someone else's - goes to auction), so it
 * does NOT live under the admin-only {@code /sc} root. "sc liquidation withdraw" and "sc
 * liquidation close" ARE admin tools, so they do. Command literals are English on purpose (the
 * user does not want Spanish command words, even though every player-facing message stays in
 * Spanish) - the internal "embargo" naming (classes, config, docs) is unaffected, only what
 * players actually type changed.
 */
public final class EmbargoCommand {

    private EmbargoCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("liquidation")
                .then(Commands.literal("vote")
                        .executes(EmbargoCommand::vote)));

        dispatcher.register(Commands.literal("sc")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("liquidation")
                        .then(Commands.literal("withdraw")
                                .executes(EmbargoCommand::withdraw))
                        .then(Commands.literal("close")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes(EmbargoCommand::close)))));
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

    private static int withdraw(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
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

    private static int close(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
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
