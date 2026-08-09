package com.sheyito.economicmaster.data;

import com.sheyito.economicmaster.EconomicMaster;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;

/**
 * All mutable EconomicMaster data (balances, subscriptions, salary timestamps) lives
 * inside the current world's save folder, so it travels with backups/world copies
 * instead of being shared/mismatched across different worlds on the same server.
 */
public final class DataPaths {

    private DataPaths() {
    }

    public static Path dataDir(MinecraftServer server) {
        return server.getWorldPath(new LevelResource(EconomicMaster.MODID));
    }
}
