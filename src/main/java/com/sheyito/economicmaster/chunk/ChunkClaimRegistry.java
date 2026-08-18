package com.sheyito.economicmaster.chunk;

import com.sheyito.economicmaster.data.ChunkClaimData;
import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.util.JsonFileUtil;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks, per player, everything about their FTB Chunks footprint: how many chunks they hold
 * claimed (drives the {@code n^1.5} pricing in {@code ChunkClaimLogic}), how many they currently
 * have force-loaded and when they were last billed rent for it, and whether an auto-unload is
 * waiting for their next login. Renamed from {@code ChunkClaimManager} (same
 * {@code chunk_claim_data.json} file, so existing servers don't lose their claim counts on
 * upgrade) when force-load rent was added - one registry, not two parallel sources of truth for
 * "chunks reclamados". Follows the manager-with-lifecycle pattern (see
 * docs/features/patronManager.md).
 *
 * <p>Deliberately has zero FTB Chunks/FTB Library imports, same as before: the only place that
 * actually calls into FTB Chunks to perform a real unload is {@code integration.FtbChunksIntegration},
 * reached only through the {@code integration.FTBChunksCompat} soft-dependency gate.
 */
public class ChunkClaimRegistry {

    private static volatile ChunkClaimRegistry instance;

    private static final double FORCE_LOAD_RENT_EXPONENT = 1.5;

    private final Path file;
    private final Map<UUID, Integer> claimCount = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> loadedCount = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastForceLoadRentDay = new ConcurrentHashMap<>();
    private final Set<UUID> pendingForceUnload = new CopyOnWriteArraySet<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private ChunkClaimRegistry(Path file) {
        this.file = file;
    }

    public static void init(MinecraftServer server) {
        ChunkClaimRegistry registry = new ChunkClaimRegistry(DataPaths.dataDir(server).resolve("chunk_claim_data.json"));
        registry.load();
        instance = registry;
    }

    public static ChunkClaimRegistry get() {
        return instance;
    }

    /** Test-support seam, not part of the mod's real lifecycle - builds an in-memory instance
     * that never touches disk. */
    public static ChunkClaimRegistry createForTesting() {
        return new ChunkClaimRegistry(Path.of("build", "test-tmp", "unused-chunk-claim-test-file.json"));
    }

    public static void installForTesting(ChunkClaimRegistry registry) {
        instance = registry;
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
        data.loadedCount.forEach((uuid, count) -> loadedCount.put(UUID.fromString(uuid), count));
        data.lastForceLoadRentDay.forEach((uuid, day) -> lastForceLoadRentDay.put(UUID.fromString(uuid), day));
        data.pendingForceUnload.forEach(uuid -> pendingForceUnload.add(UUID.fromString(uuid)));
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        ChunkClaimData data = new ChunkClaimData();
        claimCount.forEach((uuid, count) -> data.claimCount.put(uuid.toString(), count));
        loadedCount.forEach((uuid, count) -> data.loadedCount.put(uuid.toString(), count));
        lastForceLoadRentDay.forEach((uuid, day) -> data.lastForceLoadRentDay.put(uuid.toString(), day));
        pendingForceUnload.forEach(uuid -> data.pendingForceUnload.add(uuid.toString()));
        JsonFileUtil.save(file, data);
    }

    // === Chunks reclamados (sin cambios respecto a ChunkClaimManager) ===

    public int getClaimCount(UUID uuid) {
        return claimCount.getOrDefault(uuid, 0);
    }

    public void incrementClaimCount(UUID uuid) {
        claimCount.merge(uuid, 1, Integer::sum);
        dirty.set(true);
    }

    /**
     * Reverses {@link #incrementClaimCount}, floored at 0 - called when a player unclaims a
     * chunk, so the price of their next claim reflects how many chunks they currently hold, not
     * a lifetime total that only ever goes up.
     */
    public void decrementClaimCount(UUID uuid) {
        claimCount.merge(uuid, 0, (current, unused) -> Math.max(0, current - 1));
        dirty.set(true);
    }

    /**
     * Admin-only testing tool for {@code /sc chunk reset} - drops a player's count straight to
     * 0, for when repeated {@link #decrementClaimCount} calls (or a world where chunks were
     * unclaimed before this fix existed) would be tedious. Same no-refund rule as everything
     * else here: only the count changes, never the player's balance.
     */
    public void resetClaimCount(UUID uuid) {
        claimCount.remove(uuid);
        dirty.set(true);
    }

    // === Chunks force-loaded y su renta ===

    public int getLoadedCount(UUID uuid) {
        return loadedCount.getOrDefault(uuid, 0);
    }

    /**
     * {@code currentGameDay} seeds {@link #lastForceLoadRentDay} the first time this player ever
     * force-loads a chunk, so their first bill only comes due a full interval after they started
     * - not retroactively for a period before they had anything loaded.
     */
    public void incrementLoadedCount(UUID uuid, long currentGameDay) {
        loadedCount.merge(uuid, 1, Integer::sum);
        lastForceLoadRentDay.putIfAbsent(uuid, currentGameDay);
        dirty.set(true);
    }

    /** Floored at 0. Deliberately does not clear {@link #lastForceLoadRentDay} - dropping to 0
     * and force-loading again later keeps the existing billing cadence rather than resetting it,
     * a harmless simplification since it can only make the next bill land slightly earlier, never
     * later. */
    public void decrementLoadedCount(UUID uuid) {
        loadedCount.merge(uuid, 0, (current, unused) -> Math.max(0, current - 1));
        dirty.set(true);
    }

    /** Sets the loaded count back to 0 and clears any pending-unload flag - called once the real
     * FTB Chunks unload has actually happened (immediately if online, or on next login). */
    public void clearLoadedChunks(UUID uuid) {
        loadedCount.remove(uuid);
        pendingForceUnload.remove(uuid);
        dirty.set(true);
    }

    public boolean hasPendingForceUnload(UUID uuid) {
        return pendingForceUnload.contains(uuid);
    }

    public void markPendingForceUnload(UUID uuid) {
        pendingForceUnload.add(uuid);
        dirty.set(true);
    }

    /** Records that this player's force-load rent was checked this pass, whether or not they
     * could afford it - prevents re-attempting every scheduler pass for the same period. */
    public void markForceLoadRentChecked(UUID uuid, long currentGameDay) {
        lastForceLoadRentDay.put(uuid, currentGameDay);
        dirty.set(true);
    }

    /** Every player with force-loaded chunks whose rent is due (or overdue) this pass, skipping
     * anyone already waiting on a deferred auto-unload at their next login. */
    public List<UUID> playersDueForForceLoadRent(long currentGameDay, int intervalGameDays) {
        List<UUID> due = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : loadedCount.entrySet()) {
            UUID uuid = entry.getKey();
            if (entry.getValue() <= 0 || pendingForceUnload.contains(uuid)) {
                continue;
            }
            long lastDay = lastForceLoadRentDay.getOrDefault(uuid, currentGameDay);
            if (currentGameDay - lastDay >= intervalGameDays) {
                due.add(uuid);
            }
        }
        return due;
    }

    /** Same {@code base * n^1.5} shape as the chunk-claim price, but pricing what a player
     * already holds loaded ({@code n} = current count) instead of the next chunk they'd claim
     * ({@code n+1}). */
    public static double forceLoadRentFor(double forceLoadRentBase, int loadedCount) {
        return forceLoadRentBase * Math.pow(loadedCount, FORCE_LOAD_RENT_EXPONENT);
    }
}
