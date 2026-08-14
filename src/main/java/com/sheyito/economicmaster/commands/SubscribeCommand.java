package com.sheyito.economicmaster.commands;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.data.PlayerSubscription;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.subscription.SubscriptionManager;
import com.sheyito.economicmaster.util.Money;
import com.sheyito.economicmaster.util.TransactionSounds;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Fully player-to-player: "/subscribe <jugador> <dinero> <tiempo> [descripcion]" sends
 * <jugador> a proposal to pay you <dinero> every <tiempo> in-game days - exactly like
 * {@code /trade}, nothing is charged until they run "/subscribe accept" (or "/subscribe deny"
 * to decline). "/subscribe providers" lists what you're paying (to cancel via
 * "/subscribe cancel <numero>"), "/subscribe clients" lists what others are paying you.
 */
public final class SubscribeCommand {

    private SubscribeCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("subscribe")
                .then(Commands.literal("accept").executes(SubscribeCommand::accept))
                .then(Commands.literal("deny").executes(SubscribeCommand::deny))
                .then(Commands.literal("providers").executes(SubscribeCommand::providers))
                .then(Commands.literal("clients").executes(SubscribeCommand::clients))
                .then(Commands.literal("cancel")
                        .then(Commands.argument("numero", IntegerArgumentType.integer(1))
                                .executes(SubscribeCommand::cancel)))
                .then(Commands.argument("jugador", GameProfileArgument.gameProfile())
                        .then(Commands.argument("dinero", DoubleArgumentType.doubleArg(0.01))
                                .then(Commands.argument("tiempo", IntegerArgumentType.integer(1))
                                        .executes(ctx -> invite(ctx, ""))
                                        .then(Commands.argument("descripcion", StringArgumentType.greedyString())
                                                .executes(ctx -> invite(ctx, StringArgumentType.getString(ctx, "descripcion"))))))));
    }

    private static int invite(CommandContext<CommandSourceStack> ctx, String description) throws CommandSyntaxException {
        ServerPlayer receiver = ctx.getSource().getPlayerOrException();
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "jugador");
        GameProfile payerProfile = profiles.iterator().next();
        UUID payerUuid = payerProfile.getId();
        double price = DoubleArgumentType.getDouble(ctx, "dinero");
        int tiempo = IntegerArgumentType.getInteger(ctx, "tiempo");

        if (payerUuid.equals(receiver.getUUID())) {
            ctx.getSource().sendFailure(Component.literal("§cNo puedes cobrarte a ti mismo."));
            TransactionSounds.failure(receiver);
            return 0;
        }

        EconomyManager.get().trackName(payerUuid, payerProfile.getName());
        SubscriptionManager.get().invite(ctx.getSource().getServer(), receiver, payerUuid, price, tiempo, description);
        return 1;
    }

    private static int accept(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer payer = ctx.getSource().getPlayerOrException();
        if (!SubscriptionManager.get().acceptInvite(ctx.getSource().getServer(), payer)) {
            ctx.getSource().sendFailure(Component.literal("§cNo tienes ninguna propuesta de suscripcion pendiente, o no tienes saldo suficiente."));
            TransactionSounds.failure(payer);
            return 0;
        }
        TransactionSounds.success(payer);
        return 1;
    }

    private static int deny(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer payer = ctx.getSource().getPlayerOrException();
        if (!SubscriptionManager.get().denyInvite(ctx.getSource().getServer(), payer)) {
            ctx.getSource().sendFailure(Component.literal("§cNo tienes ninguna propuesta de suscripcion pendiente."));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fPropuesta rechazada."), false);
        return 1;
    }

    private static int providers(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<PlayerSubscription> providers = SubscriptionManager.get().providersFor(player.getUUID());
        if (providers.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7No le pagas a nadie. Para empezar a pagarle a alguien, esa persona debe ejecutar /subscribe <tu nombre> <dinero> <tiempo>."), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§6=== Sheyito's currency: a quien le pagas ==="), false);
        for (int i = 0; i < providers.size(); i++) {
            int number = i + 1;
            PlayerSubscription sub = providers.get(i);
            String sellerName = EconomyManager.get().getName(UUID.fromString(sub.sellerUuid));
            String suffix = sub.description == null || sub.description.isBlank() ? "" : " - " + sub.description;
            ctx.getSource().sendSuccess(() -> Component.literal("§e" + number + ". §f" + sellerName + " §7- " + Money.format(sub.price) + " cada " + sub.intervalGameDays + " dias, proximo cobro: dia " + sub.nextChargeGameDay + suffix), false);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§7Usa /subscribe cancel <numero> para cancelar uno."), false);
        return 1;
    }

    private static int clients(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        List<PlayerSubscription> clients = SubscriptionManager.get().clientsFor(player.getUUID());
        if (clients.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7Nadie te esta pagando todavia."), false);
            return 1;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("§6=== Sheyito's currency: quien te paga ==="), false);
        for (PlayerSubscription sub : clients) {
            String buyerName = EconomyManager.get().getName(UUID.fromString(sub.buyerUuid));
            String suffix = sub.description == null || sub.description.isBlank() ? "" : " - " + sub.description;
            ctx.getSource().sendSuccess(() -> Component.literal("§e" + buyerName + " §7- " + Money.format(sub.price) + " cada " + sub.intervalGameDays + " dias, proximo cobro: dia " + sub.nextChargeGameDay + suffix), false);
        }
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        int numero = IntegerArgumentType.getInteger(ctx, "numero");
        if (SubscriptionManager.get().cancelByIndex(player.getUUID(), numero)) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fCancelaste el pago numero " + numero + ". Usa /subscribe providers para ver la lista actualizada."), false);
        } else {
            ctx.getSource().sendFailure(Component.literal("§cNo tienes ningun pago con ese numero. Usa /subscribe providers para ver la lista."));
        }
        return 1;
    }
}
