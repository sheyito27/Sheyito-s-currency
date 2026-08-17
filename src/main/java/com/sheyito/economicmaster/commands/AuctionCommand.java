package com.sheyito.economicmaster.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.auction.AuctionPoolManager;
import com.sheyito.economicmaster.liquidation.LiquidationAuctionMenu;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

import java.util.Optional;

/**
 * Root-level, player-facing entry point for the bidding GUI - the command the start/bid chat
 * announcements' "[Pujar]" button runs ({@code AuctionPoolManager#announceStart}/{@code
 * #announceBid}), and the one a player is expected to type by hand day-to-day (moved out from
 * under "/liquidation" so it's shorter and easier to reach for). "/liquidation vote" stays where
 * it was - unrelated flow, see {@link LiquidationCommand}. The "puesto de subastas" villager is still
 * the only way to CHOOSE what goes up for bid in the first place.
 */
public final class AuctionCommand {

    private AuctionCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("auction")
                .executes(AuctionCommand::execute));
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        AuctionPoolManager pool = AuctionPoolManager.get();
        Optional<AuctionPoolManager.PooledItem> current = pool == null ? Optional.empty() : pool.currentAuctionItem();
        if (current.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§cNo hay ninguna subasta activa ahora mismo."));
            return 0;
        }
        if (current.get().seizedFromUuid().equals(player.getUUID())) {
            ctx.getSource().sendFailure(Component.literal("§cNo puedes pujar por tu propio objeto incautado."));
            return 0;
        }

        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new LiquidationAuctionMenu(id, inv, player.getUUID()),
                Component.literal("Subasta")));
        return 1;
    }
}
