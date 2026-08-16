package com.sheyito.economicmaster.auction;

import com.sheyito.economicmaster.data.AuctionPoolData;
import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.util.ItemStackJson;
import com.sheyito.economicmaster.util.JsonFileUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pure storage for items that won an embargo vote (see {@code EmbargoManager}) - a FIFO queue an
 * admin drains one at a time with "/sc liquidation withdraw". Nothing here is automatic: no auction
 * logic, no expiry, no distribution - the community decides what to do with a retrieved item on
 * their own. Follows the manager-with-lifecycle pattern (docs/features/patronManager.md).
 */
public class AuctionPoolManager {

    private static volatile AuctionPoolManager instance;

    private final Path file;
    private final List<PooledItem> items = new CopyOnWriteArrayList<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public record PooledItem(ItemStack stack, UUID seizedFromUuid, String seizedFromName, long addedAtGameDay) {
    }

    private AuctionPoolManager(Path file) {
        this.file = file;
    }

    public static void init(MinecraftServer server) {
        AuctionPoolManager manager = new AuctionPoolManager(DataPaths.dataDir(server).resolve("auction_pool_data.json"));
        manager.load();
        instance = manager;
    }

    public static AuctionPoolManager get() {
        return instance;
    }

    public static void installForTesting(AuctionPoolManager manager) {
        instance = manager;
    }

    /** Test-support seam, not part of the mod's real lifecycle - builds an in-memory instance
     * that never touches disk. */
    public static AuctionPoolManager createForTesting() {
        return new AuctionPoolManager(Path.of("build", "test-tmp", "unused-auction-pool-test-file.json"));
    }

    public static void shutdown() {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    private void load() {
        AuctionPoolData data = JsonFileUtil.loadOrCreate(file, AuctionPoolData.class, AuctionPoolData::empty);
        for (AuctionPoolData.PooledItemRecord record : data.items) {
            items.add(new PooledItem(
                    ItemStackJson.decode(record.item),
                    UUID.fromString(record.seizedFromUuid),
                    record.seizedFromName,
                    record.addedAtGameDay));
        }
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        AuctionPoolData data = new AuctionPoolData();
        for (PooledItem pooled : items) {
            AuctionPoolData.PooledItemRecord record = new AuctionPoolData.PooledItemRecord();
            record.item = ItemStackJson.encode(pooled.stack());
            record.seizedFromUuid = pooled.seizedFromUuid().toString();
            record.seizedFromName = pooled.seizedFromName();
            record.addedAtGameDay = pooled.addedAtGameDay();
            data.items.add(record);
        }
        JsonFileUtil.save(file, data);
    }

    public void add(ItemStack stack, UUID seizedFromUuid, String seizedFromName, long gameDay) {
        items.add(new PooledItem(stack.copy(), seizedFromUuid, seizedFromName, gameDay));
        dirty.set(true);
    }

    public List<PooledItem> list() {
        return List.copyOf(items);
    }

    /** Pops the oldest pooled item (FIFO), for "/sc liquidation withdraw". Empty if the pool is empty. */
    public Optional<PooledItem> retrieveNext() {
        if (items.isEmpty()) {
            return Optional.empty();
        }
        PooledItem next = items.remove(0);
        dirty.set(true);
        return Optional.of(next);
    }
}
