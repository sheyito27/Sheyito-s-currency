package com.sheyito.economicmaster.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sheyito.economicmaster.EconomicMaster;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Home of "/sheyitoscurrency reward <jugador>" - the single command this mod expects to be
 * wired into FTB Quests' "Command" reward type. Every quest pays the same flat amount
 * (quests_rewards.json's {@code amount}, 50 by default) - paste the exact same reward into
 * every quest, no per-quest setup needed:
 *   sheyitoscurrency reward @p
 * where {@code @p} (nearest player) is what FTB Quests resolves to "the player who completed
 * the quest" when the Command Reward is run with "Run as Player" OFF (console mode, which is
 * also what grants the permission level 2 this command requires). An optional trailing amount
 * overrides the configured value for that one call: "sheyitoscurrency reward @p 120".
 */
public final class EconomicMasterCommand {

    private EconomicMasterCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(EconomicMaster.MODID)
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("reward")
                        .then(Commands.argument("jugador", EntityArgument.player())
                                .executes(ctx -> reward(ctx, ConfigManager.questRewards().amount))
                                .then(Commands.argument("monto", DoubleArgumentType.doubleArg(0.01))
                                        .executes(ctx -> reward(ctx, DoubleArgumentType.getDouble(ctx, "monto")))))));
    }

    private static int reward(CommandContext<CommandSourceStack> ctx, double amount) throws CommandSyntaxException {
        ServerPlayer player = EntityArgument.getPlayer(ctx, "jugador");
        EconomicMaster.LOGGER.info("Sheyito's currency: /{} reward invocado para {} por {}", EconomicMaster.MODID, player.getGameProfile().getName(), amount);

        EconomyManager.get().giveEarned(player.getUUID(), amount);
        EconomyManager.get().trackName(player.getUUID(), player.getGameProfile().getName());

        ctx.getSource().sendSuccess(() -> Component.literal("§a[Sheyito's currency] §fRecompensa de mision aplicada a " + player.getGameProfile().getName() + ": " + Money.format(amount) + "."), true);
        player.sendSystemMessage(Component.literal("§6✦ §a[Recompensa de Mision] §f+" + Money.format(amount) + " §7añadidas a tu saldo."));
        return 1;
    }
}
