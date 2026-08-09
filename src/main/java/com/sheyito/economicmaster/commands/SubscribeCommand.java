package com.sheyito.economicmaster.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.data.PlayerSubscription;
import com.sheyito.economicmaster.subscription.SubscriptionManager;
import com.sheyito.economicmaster.util.Money;
import com.sheyito.economicmaster.util.TransactionSounds;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Fully player-to-player: "/subscribe offer <precio>" turns you into a seller of a paid
 * service at your own price, other players pay in via "/subscribe <jugador>", and get
 * billed automatically every intervalGameDays (subscriptions.json) from then on.
 */
public final class SubscribeCommand {

    private SubscribeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("subscribe")
                .executes(SubscribeCommand::status)
                .then(Commands.literal("offer")
                        .then(Commands.argument("precio", DoubleArgumentType.doubleArg(0.01))
                                .executes(SubscribeCommand::offer)))
                .then(Commands.literal("stop").executes(SubscribeCommand::stop))
                .then(Commands.literal("offers").executes(SubscribeCommand::listOffers))
                .then(Commands.literal("cancel").executes(SubscribeCommand::cancel))
                .then(Commands.argument("jugador", GameProfileArgument.gameProfile())
                        .executes(SubscribeCommand::subscribe)));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        SubscriptionManager manager = SubscriptionManager.get();

        PlayerSubscription sub = manager.getSubscription(player.getUUID());
        if (sub == null || !sub.active) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7No estás suscrito a nadie. Usa /subscribe <jugador> para suscribirte."), false);
        } else {
            String sellerName = EconomyManager.get().getName(UUID.fromString(sub.sellerUuid));
            ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fSuscrito a §e" + sellerName + " §fpor " + Money.format(sub.price) + " cada renovacion - proximo cobro: dia " + sub.nextChargeGameDay + "."), false);
        }

        Double myOffer = manager.getOfferPrice(player.getUUID());
        if (myOffer != null) {
            long subscriberCount = manager.subscriberCount(player.getUUID());
            ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fOfreces tu servicio por " + Money.format(myOffer) + " - suscriptores actuales: §e" + subscriberCount), false);
        }
        return 1;
    }

    private static int offer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        double price = DoubleArgumentType.getDouble(ctx, "precio");
        SubscriptionManager.get().setOffer(player.getUUID(), price);
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fAhora ofreces tu servicio de suscripcion por " + Money.format(price) + " cada renovacion. Otros jugadores pueden usar /subscribe " + player.getGameProfile().getName() + "."), false);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!SubscriptionManager.get().hasOffer(player.getUUID())) {
            ctx.getSource().sendFailure(Component.literal("§cNo estás ofreciendo ningún servicio."));
            return 0;
        }
        SubscriptionManager.get().removeOffer(ctx.getSource().getServer(), player.getUUID());
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fDejaste de ofrecer tu servicio de suscripcion. Tus suscriptores fueron notificados."), false);
        return 1;
    }

    private static int listOffers(CommandContext<CommandSourceStack> ctx) {
        Map<UUID, Double> offers = SubscriptionManager.get().getOffers();
        if (offers.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7Nadie esta ofreciendo servicios de suscripcion todavia."), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§6=== Sheyito's currency: servicios de suscripcion disponibles ==="), false);
        offers.forEach((seller, price) -> {
            String name = EconomyManager.get().getName(seller);
            ctx.getSource().sendSuccess(() -> Component.literal("§e" + name + " §f- " + Money.format(price) + " (/subscribe " + name + ")"), false);
        });
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (SubscriptionManager.get().cancel(player.getUUID())) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fCancelaste tu suscripcion."), false);
        } else {
            ctx.getSource().sendFailure(Component.literal("§cNo estás suscrito a nadie."));
        }
        return 1;
    }

    private static int subscribe(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer buyer = ctx.getSource().getPlayerOrException();
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "jugador");
        GameProfile sellerProfile = profiles.iterator().next();
        UUID sellerUuid = sellerProfile.getId();

        if (sellerUuid.equals(buyer.getUUID())) {
            ctx.getSource().sendFailure(Component.literal("§cNo puedes suscribirte a ti mismo."));
            TransactionSounds.failure(buyer);
            return 0;
        }

        Double price = SubscriptionManager.get().getOfferPrice(sellerUuid);
        if (price == null) {
            ctx.getSource().sendFailure(Component.literal("§c" + sellerProfile.getName() + " no ofrece ningun servicio de suscripcion. Usa /subscribe offers para ver los disponibles."));
            TransactionSounds.failure(buyer);
            return 0;
        }

        if (!SubscriptionManager.get().subscribe(ctx.getSource().getServer(), buyer, sellerUuid)) {
            ctx.getSource().sendFailure(Component.literal("§cNo tienes saldo suficiente. Necesitas " + Money.format(price) + "."));
            TransactionSounds.failure(buyer);
            return 0;
        }

        EconomyManager.get().trackName(sellerUuid, sellerProfile.getName());
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fTe suscribiste a " + sellerProfile.getName() + " por " + Money.format(price) + "."), false);
        TransactionSounds.success(buyer);
        return 1;
    }
}
