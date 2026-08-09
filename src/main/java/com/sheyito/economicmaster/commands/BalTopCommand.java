package com.sheyito.economicmaster.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BalTopCommand {

    private static final int PAGE_SIZE = 10;
    private static final int MAX_ENTRIES = 100;

    private BalTopCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("baltop")
                .executes(ctx -> show(ctx, 1))
                .then(Commands.argument("pagina", IntegerArgumentType.integer(1))
                        .executes(ctx -> show(ctx, IntegerArgumentType.getInteger(ctx, "pagina")))));
    }

    private static int show(CommandContext<CommandSourceStack> ctx, int page) {
        List<Map.Entry<UUID, Double>> top = EconomyManager.get().top(MAX_ENTRIES);
        int totalPages = Math.max(1, (int) Math.ceil(top.size() / (double) PAGE_SIZE));
        int clampedPage = Math.min(Math.max(page, 1), totalPages);
        int start = (clampedPage - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, top.size());

        ctx.getSource().sendSuccess(() -> Component.literal("§6=== Sheyito's currency: Top saldos (pagina " + clampedPage + "/" + totalPages + ") ==="), false);
        for (int i = start; i < end; i++) {
            Map.Entry<UUID, Double> entry = top.get(i);
            String name = EconomyManager.get().getName(entry.getKey());
            int rank = i + 1;
            ctx.getSource().sendSuccess(() -> Component.literal("§e#" + rank + " §f" + name + " §7- §a" + Money.format(entry.getValue())), false);
        }
        if (top.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("§7Todavia no hay jugadores con saldo registrado."), false);
        }
        return 1;
    }
}
