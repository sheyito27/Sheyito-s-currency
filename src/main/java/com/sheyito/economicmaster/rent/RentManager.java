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
 * Charges a progressive tax on each player's 7-day GROSS earnings - see {@link RentLogic}.
 * Follows the manager-with-lifecycle pattern (docs/features/patronManager.md).
 *
 * <p>"Ganancias" is a running accumulator fed by {@link EconomyManager#give} ({@link #trackGain}),
 * not a before/after balance comparison - confirmed with the user against an earlier net-delta
 * design: earning 10,000 and separately losing 20,000 in the same period still owes tax on the
 * 10,000 earned, even though the player is down overall. Losing money is spending, unrelated to
 * this tax; it never offsets a gain, past or future.
 *
 * <p>Unlike every other sink in this mod, charging this tax uses {@link EconomyManager#charge} -
 * not {@link EconomyManager#take} - so it can genuinely push a player into a negative balance
 * instead of being skipped when they can't afford it. That is the point, confirmed with the user:
 * this is meant to be able to end in "banca rota", and {@code setBalance}'s own >=0-to-negative
 * hook already wires that straight into the embargo grace period - the first real, organic
 * trigger for it, no changes needed on that side.
 */
public class RentManager {

    private static volatile RentManager instance;

    private final Path file;
    private final Map<UUID, Record> records = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private static class Record {
        long lastRentDay = -1;
        double accumulatedGains;
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
            record.accumulatedGains = stored.accumulatedGains;
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
            stored.accumulatedGains = record.accumulatedGains;
            data.records.put(uuid.toString(), stored);
        });
        JsonFileUtil.save(file, data);
    }

    /** Called from {@link EconomyManager#give} - every gross gain accumulates here, regardless
     * of what the player later spends or loses. A brand new player's record starts with
     * {@code lastRentDay = -1} ("never billed yet"), which {@link #processDueRent} treats as
     * immediately due - there is no pre-existing balance to worry about double-counting, since
     * gains only ever accumulate here from this point forward. */
    public void trackGain(UUID uuid, double amount) {
        Record record = records.computeIfAbsent(uuid, k -> new Record());
        record.accumulatedGains += amount;
        dirty.set(true);
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

        for (Map.Entry<UUID, Record> entry : records.entrySet()) {
            UUID uuid = entry.getKey();
            Record record = entry.getValue();
            boolean due = record.lastRentDay < 0 || currentDay - record.lastRentDay >= config.intervalGameDays;
            if (!due) {
                continue;
            }

            chargeAndAdvance(server, economy, config, uuid, record, currentDay);
        }
    }

    /**
     * Admin-only testing tool for {@code /sc rent force} - charges this one player's
     * accumulated gains right now, ignoring whether {@code intervalGameDays} has actually
     * elapsed (and even whether they've ever been checkpointed at all). A no-op if they've never
     * earned anything tracked - nothing accumulated, nothing to force.
     */
    public void forceProcess(MinecraftServer server, UUID uuid) {
        RentConfig config = ConfigManager.rent();
        if (!config.enabled) {
            return;
        }
        Record record = records.get(uuid);
        if (record == null) {
            return;
        }
        chargeAndAdvance(server, EconomyManager.get(), config, uuid, record, GameTime.currentDay(server));
    }

    private void chargeAndAdvance(MinecraftServer server, EconomyManager economy, RentConfig config,
                                   UUID uuid, Record record, long currentDay) {
        double gains = record.accumulatedGains;
        if (gains > 0) {
            double tax = RentLogic.taxFor(gains, config.profitBrackets);
            economy.charge(uuid, tax);
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                player.sendSystemMessage(Component.literal("§6[Sheyito's currency] §fRenta semanal: -"
                        + Money.format(tax) + " sobre " + Money.format(gains) + " de ganancias."));
            }
        }

        record.accumulatedGains = 0;
        record.lastRentDay = currentDay;
        dirty.set(true);
    }
}
