package com.sheyito.economicmaster.util;

import com.sheyito.economicmaster.EconomicMaster;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * Executes configured hook commands (subscription grant/revoke commands, etc.) from the
 * console command source, expanding %player% and %uuid% placeholders.
 */
public final class ConsoleCommandRunner {

    private ConsoleCommandRunner() {
    }

    public static void run(MinecraftServer server, String rawCommand, String playerName, UUID uuid) {
        if (rawCommand == null || rawCommand.isBlank()) {
            return;
        }
        String command = rawCommand.replace("%player%", playerName).replace("%uuid%", uuid.toString());
        CommandSourceStack source = server.createCommandSourceStack();
        try {
            server.getCommands().performPrefixedCommand(source, command);
        } catch (Exception e) {
            EconomicMaster.LOGGER.error("Fallo al ejecutar el comando de hook '{}'", command, e);
        }
    }
}
