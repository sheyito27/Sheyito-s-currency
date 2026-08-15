package com.sheyito.economicmaster.dimension;

import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.data.DimensionUnlockData;
import com.sheyito.economicmaster.util.JsonFileUtil;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks, per player, which non-overworld dimensions they've already paid to unlock. Follows
 * the manager-with-lifecycle pattern (see docs/features/patronManager.md): instantiated once
 * per server session by {@code ServerLifecycleHandler}, saved on every mutation-triggering tick
 * pass plus on server stop.
 */
public class DimensionUnlockManager {

    private static volatile DimensionUnlockManager instance;

    private final Path file;
    private final Map<UUID, Set<String>> unlockedDimensions = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private DimensionUnlockManager(Path file) {
        this.file = file;
    }

    public static void init(MinecraftServer server) {
        DimensionUnlockManager manager = new DimensionUnlockManager(DataPaths.dataDir(server).resolve("dimension_unlocks.json"));
        manager.load();
        instance = manager;
    }

    public static DimensionUnlockManager get() {
        return instance;
    }

    /**
     * Test-support seam, not part of the mod's real lifecycle - builds an in-memory instance
     * that never touches disk.
     */
    public static DimensionUnlockManager createForTesting() {
        return new DimensionUnlockManager(Path.of("build", "test-tmp", "unused-dimension-unlock-test-file.json"));
    }

    public static void shutdown() {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    private void load() {
        DimensionUnlockData data = JsonFileUtil.loadOrCreate(file, DimensionUnlockData.class, DimensionUnlockData::empty);
        data.unlockedDimensions.forEach((uuid, dimensionIds) -> {
            Set<String> set = ConcurrentHashMap.newKeySet(dimensionIds.size());
            set.addAll(dimensionIds);
            unlockedDimensions.put(UUID.fromString(uuid), set);
        });
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        DimensionUnlockData data = new DimensionUnlockData();
        unlockedDimensions.forEach((uuid, dimensionIds) -> data.unlockedDimensions.put(uuid.toString(), dimensionIds));
        JsonFileUtil.save(file, data);
    }

    public boolean isUnlocked(UUID uuid, ResourceKey<Level> dimension) {
        Set<String> unlocked = unlockedDimensions.get(uuid);
        return unlocked != null && unlocked.contains(dimension.location().toString());
    }

    public void unlock(UUID uuid, ResourceKey<Level> dimension) {
        unlockedDimensions.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).add(dimension.location().toString());
        dirty.set(true);
    }
}
