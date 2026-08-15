package com.sheyito.economicmaster.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.config.MonopolyEventEntry;
import com.sheyito.economicmaster.monopoly.EventType;
import com.sheyito.economicmaster.monopoly.MonopolyManager;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * Comandos del evento económico "Monopoly":
 * <ul>
 *   <li>/monopoly status — estado del evento activo y cuándo llega el siguiente (público).</li>
 *   <li>/monopoly coinflip &lt;cantidad&gt; [jugador] — cara o cruz contra La Casa o contra otro jugador.</li>
 *   <li>/monopoly accept / deny — aceptar o rechazar un reto de cara o cruz pendiente.</li>
 *   <li>/monopoly roll [id] / end — (OP 2) forzar un sorteo o terminar el evento actual.</li>
 * </ul>
 */
public final class MonopolyCommand {

    private MonopolyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("monopoly")
                .then(Commands.literal("status").executes(MonopolyCommand::status))
                .then(Commands.literal("coinflip")
                        .then(Commands.argument("cantidad", DoubleArgumentType.doubleArg(0.01))
                                .executes(ctx -> coinflipVsHouse(ctx))
                                .then(Commands.argument("jugador", EntityArgument.player())
                                        .executes(ctx -> inviteCoinflip(ctx)))))
                .then(Commands.literal("accept").executes(MonopolyCommand::acceptCoinflip))
                .then(Commands.literal("deny").executes(MonopolyCommand::denyCoinflip))
                .then(Commands.literal("roll")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> roll(ctx, null))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> roll(ctx, StringArgumentType.getString(ctx, "id")))))
                .then(Commands.literal("end")
                        .requires(src -> src.hasPermission(2))
                        .executes(MonopolyCommand::end)));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        MonopolyManager monopoly = MonopolyManager.get();
        if (monopoly == null) {
            ctx.getSource().sendFailure(Component.literal("§c[Monopoly] §fEl sistema de eventos no esta disponible."));
            return 0;
        }

        MinecraftServer server = ctx.getSource().getServer();
        if (!monopoly.isActive()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§6[Monopoly] §fNo hay ningun evento activo. Proximo evento en " + formatTicks(monopoly.ticksUntilNext(server)) + "."), false);
            return 1;
        }

        String description = describeEvent(monopoly);
        ctx.getSource().sendSuccess(() -> Component.literal("§6[Monopoly] §fEvento activo: §e" + monopoly.currentEventId()
                + "§f (" + description + "). Proximo evento en " + formatTicks(monopoly.ticksUntilNext(server)) + "."), false);
        return 1;
    }

    private static String describeEvent(MonopolyManager monopoly) {
        MonopolyEventEntry entry = monopoly.currentEventConfig();
        if (entry == null) {
            return "desconocido";
        }
        EventType type = EventType.fromId(entry.type);
        return switch (type) {
            case SALARY_MULTIPLIER -> "salarios " + String.format(Locale.US, "x%.2f", monopoly.salaryMultiplier());
            case QUEST_REWARD_MULTIPLIER -> "misiones " + String.format(Locale.US, "x%.2f", monopoly.questRewardMultiplier());
            case MOB_WANTED -> "mob buscado: " + monopoly.wantedMob() + " (" + Money.format(monopoly.wantedBounty())
                    + (monopoly.mobWantedMaxKills() > 0
                        ? ", " + monopoly.currentMobKills() + "/" + monopoly.mobWantedMaxKills() + " muertes" : "")
                    + ")" + (monopoly.mobBountyExhausted() ? " — recompensa agotada" : "");
            case HOUSE_COINFLIP -> "cara o cruz, comision " + String.format(Locale.US, "%.1f", monopoly.houseCommission() * 100) + "%";
            case WINDFALL -> "efecto instantaneo: " + monopoly.currentEffect();
            case null -> "desconocido";
        };
    }

    private static int coinflipVsHouse(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        double amount = DoubleArgumentType.getDouble(ctx, "cantidad");
        MonopolyManager.get().coinflipVsHouse(ctx.getSource().getServer(), player, amount);
        return 1;
    }

    private static int inviteCoinflip(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer challenger = ctx.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(ctx, "jugador");
        double amount = DoubleArgumentType.getDouble(ctx, "cantidad");
        MonopolyManager.get().inviteCoinflip(ctx.getSource().getServer(), challenger, target, amount);
        return 1;
    }

    private static int acceptCoinflip(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MonopolyManager.get().acceptCoinflip(ctx.getSource().getServer(), player);
        return 1;
    }

    private static int denyCoinflip(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        MonopolyManager.get().denyCoinflip(ctx.getSource().getServer(), player);
        return 1;
    }

    private static int roll(CommandContext<CommandSourceStack> ctx, String id) throws CommandSyntaxException {
        MonopolyManager.get().forceRoll(ctx.getSource().getServer(), id);
        ctx.getSource().sendSuccess(() -> Component.literal("§6[Monopoly] §fSorteo forzado."), true);
        return 1;
    }

    private static int end(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        MonopolyManager.get().endNow(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("§6[Monopoly] §fEvento actual finalizado."), true);
        return 1;
    }

    private static String formatTicks(long ticks) {
        long minutes = ticks / 1200;
        if (minutes < 1) {
            return (ticks / 20) + "s";
        }
        long hours = minutes / 60;
        return hours > 0 ? hours + "h " + (minutes % 60) + "min" : minutes + "min";
    }
}
