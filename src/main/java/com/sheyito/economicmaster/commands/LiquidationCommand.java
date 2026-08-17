package com.sheyito.economicmaster.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.auction.AuctionPoolManager;
import com.sheyito.economicmaster.liquidation.LiquidationManager;
import com.sheyito.economicmaster.liquidation.LiquidationVoteMenu;
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
 * secret ballot on which seized item goes to auction), so it does NOT live under the admin-only
 * {@code /sc} root - bidding itself is the even shorter root-level {@code /auction} ({@link
 * AuctionCommand}). "sc liquidation withdraw" and "sc liquidation close" ARE admin tools, so they
 * stay under {@code /sc}. Command literals are English on purpose (the user does not want Spanish
 * command words, even though every player-facing message stays in Spanish) - the internal Java
 * naming (classes, config field/method names) matches now too (package {@code liquidation},
 * {@code LiquidationManager}, ...); only the docs (docs/features/embargoDeudas.md) and the on-disk
 * config/data file names (embargo.json, embargo_data.json) still say "embargo", left alone on
 * purpose to avoid orphaning an existing dev server's config/world data.
 */
public final class LiquidationCommand {

    private LiquidationCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("liquidation")
                .then(Commands.literal("vote")
                        .executes(LiquidationCommand::vote)));

        dispatcher.register(Commands.literal("sc")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("liquidation")
                        .then(Commands.literal("withdraw")
                                .executes(LiquidationCommand::withdraw))
                        .then(Commands.literal("close")
                                .then(Commands.argument("player", GameProfileArgument.gameProfile())
                                        .executes(LiquidationCommand::close)))));
    }

    private static int vote(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        LiquidationManager manager = LiquidationManager.get();
        Optional<Long> voteId = manager == null ? Optional.empty() : manager.openVoteFor(player.getUUID());
        if (voteId.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cNo hay ninguna votación de embargo activa en la que puedas participar."));
            return 0;
        }

        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new LiquidationVoteMenu(id, inv, voteId.get(), player.getUUID()),
                Component.literal("Votación de embargo")));
        return 1;
    }

    private static int withdraw(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer admin = ctx.getSource().getPlayerOrException();
        Optional<AuctionPoolManager.PooledItem> next = AuctionPoolManager.get().retrieveNext();
        if (next.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§cLa pool de subastas está vacía."));
            return 0;
        }

        AuctionPoolManager.PooledItem pooled = next.get();
        // Built BEFORE placeItemBackInInventory() runs, on purpose - same "Air x0" bug already
        // fixed once in AuctionPoolManager#closeCurrentAuction: that call mutates the ItemStack in
        // place (splits it down to empty as it inserts), so reading it for the message after would
        // describe an already-empty stack.
        Component message = Component.literal("§a[Sheyito's currency] §fRetiraste de la pool: "
                + pooled.stack().getHoverName().getString() + " x" + pooled.stack().getCount()
                + " (incautado a " + pooled.seizedFromName() + ").");
        admin.getInventory().placeItemBackInInventory(pooled.stack());
        ctx.getSource().sendSuccess(() -> message, true);
        return 1;
    }

    private static int close(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        GameProfile target = profiles.iterator().next();
        UUID uuid = target.getId();

        LiquidationManager manager = LiquidationManager.get();
        boolean closed = manager != null && manager.forceCloseOldestVote(uuid, ctx.getSource().getServer());
        if (!closed) {
            ctx.getSource().sendFailure(Component.literal(
                    "§cNo hay ninguna votación de embargo activa para " + target.getName() + "."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fForzado el cierre de la "
                + "votación de embargo más antigua de " + target.getName() + "."), true);
        return 1;
    }
}
