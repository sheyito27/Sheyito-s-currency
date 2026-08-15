package com.sheyito.economicmaster.chunk;

import com.sheyito.economicmaster.data.ChunkClaimData;
import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.util.JsonFileUtil;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks, per player, how many chunks they've claimed via FTB Chunks so far - drives the
 * quadratic pricing in {@code ChunkClaimLogic}. Follows the manager-with-lifecycle pattern (see
 * docs/features/patronManager.md): instantiated once per server session by
 * {@code ServerLifecycleHandler}, saved on every mutation-triggering tick pass plus on server
 * stop.
 */
public class ChunkClaimManager {

    private static volatile ChunkClaimManager instance;

    private final Path file;
    private final Map<UUID, Integer> claimCount = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private ChunkClaimManager(Path file) {
        this.file = file;
    }

    public static void init(MinecraftServer server) {
        ChunkClaimManager manager = new ChunkClaimManager(DataPaths.dataDir(server).resolve("chunk_claim_data.json"));
        manager.load();
        instance = manager;
    }

    public static ChunkClaimManager get() {
        return instance;
    }

    /** Test-support seam, not part of the mod's real lifecycle - builds an in-memory instance
     * that never touches disk. */
    public static ChunkClaimManager createForTesting() {
        return new ChunkClaimManager(Path.of("build", "test-tmp", "unused-chunk-claim-test-file.json"));
    }

    public static void shutdown() {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    private void load() {
        ChunkClaimData data = JsonFileUtil.loadOrCreate(file, ChunkClaimData.class, ChunkClaimData::empty);
        data.claimCount.forEach((uuid, count) -> claimCount.put(UUID.fromString(uuid), count));
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        ChunkClaimData data = new ChunkClaimData();
        claimCount.forEach((uuid, count) -> data.claimCount.put(uuid.toString(), count));
        JsonFileUtil.save(file, data);
    }

    public int getClaimCount(UUID uuid) {
        return claimCount.getOrDefault(uuid, 0);
    }

    public void incrementClaimCount(UUID uuid) {
        claimCount.merge(uuid, 1, Integer::sum);
        dirty.set(true);
    }
}
