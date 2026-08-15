package com.sheyito.economicmaster.rent;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.RentConfig;
import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.data.RentData;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.GameTime;
import com.sheyito.economicmaster.util.JsonFileUtil;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Charges a progressive tax on each player's 7-day PROFIT (not net worth) - see
 * {@link RentLogic}. Follows the manager-with-lifecycle pattern (docs/features/patronManager.md).
 * Every player with a tracked balance is a candidate ({@link EconomyManager#top} enumerates all
 * of them without needing a new getter on EconomyManager); a player's first appearance only
 * records a baseline, it never charges for time before they were being tracked.
 */
public class RentManager {

    private static volatile RentManager instance;

    private final Path file;
    private final Map<UUID, Record> records = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private static class Record {
        long lastRentDay;
        double balanceSnapshot;
    }

    private RentManager(Path file) {
        this.file = file;
    }

    public static void init(MinecraftServer server) {
        RentManager manager = new RentManager(DataPaths.dataDir(server).resolve("rent_data.json"));
        manager.load();
        instance = manager;
    }

    public static RentManager get() {
        return instance;
    }

    /** Test-support seam, not part of the mod's real lifecycle - builds an in-memory instance
     * that never touches disk. */
    public static RentManager createForTesting() {
        return new RentManager(Path.of("build", "test-tmp", "unused-rent-test-file.json"));
    }

    public static void installForTesting(RentManager manager) {
        instance = manager;
    }

    public static void shutdown() {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    private void load() {
        RentData data = JsonFileUtil.loadOrCreate(file, RentData.class, RentData::empty);
        data.records.forEach((uuid, stored) -> {
            Record record = new Record();
            record.lastRentDay = stored.lastRentDay;
            record.balanceSnapshot = stored.balanceSnapshot;
            records.put(UUID.fromString(uuid), record);
        });
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        RentData data = new RentData();
        records.forEach((uuid, record) -> {
            RentData.RentRecord stored = new RentData.RentRecord();
            stored.lastRentDay = record.lastRentDay;
            stored.balanceSnapshot = record.balanceSnapshot;
            data.records.put(uuid.toString(), stored);
        });
        JsonFileUtil.save(file, data);
    }

    /** Called periodically (~30s cadence, day precision is all this needs) by
     * {@code EconomicMasterScheduler}. */
    public void processDueRent(MinecraftServer server) {
        RentConfig config = ConfigManager.rent();
        if (!config.enabled) {
            return;
        }
        EconomyManager economy = EconomyManager.get();
        long currentDay = GameTime.currentDay(server);

        for (Map.Entry<UUID, Double> entry : economy.top(Integer.MAX_VALUE)) {
            UUID uuid = entry.getKey();
            double balance = entry.getValue();
            Record record = records.get(uuid);

            if (record == null) {
                record = new Record();
                record.lastRentDay = currentDay;
                record.balanceSnapshot = balance;
                records.put(uuid, record);
                dirty.set(true);
                continue;
            }

            if (currentDay - record.lastRentDay < config.intervalGameDays) {
                continue;
            }

            double profit = Math.max(0.0, balance - record.balanceSnapshot);
            if (profit > 0) {
                double tax = RentLogic.taxFor(profit, config.profitBrackets);
                if (economy.take(uuid, tax)) {
                    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    if (player != null) {
                        player.sendSystemMessage(Component.literal("§6[Sheyito's currency] §fRenta semanal: -"
                                + Money.format(tax) + " sobre " + Money.format(profit) + " de ganancias."));
                    }
                }
            }

            record.lastRentDay = currentDay;
            record.balanceSnapshot = economy.getBalance(uuid);
            dirty.set(true);
        }
    }
}
