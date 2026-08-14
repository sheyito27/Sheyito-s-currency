package com.sheyito.economicmaster.economy;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.data.EconomyData;
import com.sheyito.economicmaster.util.JsonFileUtil;
import com.sheyito.economicmaster.util.LevelCurve;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single source of truth for player balances. Instantiated once per server session
 * by {@code ServerLifecycleHandler} and saved on every mutation-triggering tick pass
 * plus on server stop, so a crash loses at most a few seconds of activity.
 */
public class EconomyManager {

    private static volatile EconomyManager instance;

    private final Path file;
    private final Map<UUID, Double> balances = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<UUID, Double> xp = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private EconomyManager(Path file) {
        this.file = file;
    }

    public static void init(MinecraftServer server) {
        EconomyManager manager = new EconomyManager(DataPaths.dataDir(server).resolve("balances.json"));
        manager.load();
        instance = manager;
    }

    public static EconomyManager get() {
        return instance;
    }

    /**
     * Test-support seam, not part of the mod's real lifecycle - production code must always
     * go through {@link #init(MinecraftServer)}. Builds an in-memory instance that never
     * touches disk (no load(), no save() called) so other managers' tests (subscriptions,
     * trades) can exercise real balance/XP math without a running server.
     */
    public static EconomyManager createForTesting() {
        return new EconomyManager(Path.of("build", "test-tmp", "unused-economy-test-file.json"));
    }

    /** Test-support seam: installs {@code manager} as the active singleton {@link #get()} returns. */
    public static void installForTesting(EconomyManager manager) {
        instance = manager;
    }

    public static void shutdown() {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    private void load() {
        EconomyData data = JsonFileUtil.loadOrCreate(file, EconomyData.class, EconomyData::empty);
        data.balances.forEach((uuid, amount) -> balances.put(UUID.fromString(uuid), amount));
        data.names.forEach((uuid, name) -> names.put(UUID.fromString(uuid), name));
        data.xp.forEach((uuid, value) -> xp.put(UUID.fromString(uuid), value));
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        EconomyData data = new EconomyData();
        balances.forEach((uuid, amount) -> data.balances.put(uuid.toString(), amount));
        names.forEach((uuid, name) -> data.names.put(uuid.toString(), name));
        xp.forEach((uuid, value) -> data.xp.put(uuid.toString(), value));
        JsonFileUtil.save(file, data);
    }

    public void trackName(UUID uuid, String name) {
        names.put(uuid, name);
        dirty.set(true);
    }

    public String getName(UUID uuid) {
        return names.getOrDefault(uuid, uuid.toString());
    }

    public double getBalance(UUID uuid) {
        return balances.getOrDefault(uuid, ConfigManager.general().startingBalance);
    }

    /**
     * No floor at 0 here on purpose: a negative result is what lets a player end up in debt
     * (see {@code DebtManager}). Every caller that must never go negative enforces that itself
     * - {@link #take} validates funds before calling this, and {@code /eco set} restricts its
     * Brigadier argument to non-negative values - so this stays a plain, unclamped setter.
     */
    public void setBalance(UUID uuid, double amount) {
        balances.put(uuid, Money.round(amount));
        dirty.set(true);
    }

    public void give(UUID uuid, double amount) {
        setBalance(uuid, getBalance(uuid) + amount);
    }

    /**
     * Unlike {@link #give}/{@link #take}, this never validates funds and can leave the balance
     * negative - the sole overdraft entry point in the economy, used by the death-penalty debt
     * mechanic and by {@code /eco charge} for admin testing. Every other transaction path
     * ({@code /pay}, {@code /trade}, shops, salary) keeps going through {@link #take}, which
     * still refuses to go negative.
     */
    public void charge(UUID uuid, double amount) {
        setBalance(uuid, getBalance(uuid) - amount);
    }

    /**
     * Like {@link #give}, but also counts as "earned" income toward the player's level
     * (kills, quest rewards, salary, subscription income received). Admin adjustments
     * ({@code /eco give}) and peer-to-peer payments ({@code /pay}) intentionally use plain
     * {@link #give} instead, so players can't farm levels by shuffling money between alts.
     */
    public void giveEarned(UUID uuid, double amount) {
        give(uuid, amount);
        if (amount > 0) {
            addXp(uuid, amount * ConfigManager.salary().xpPerCoin);
        }
    }

    private void addXp(UUID uuid, double amount) {
        xp.put(uuid, Math.max(0.0, xp.getOrDefault(uuid, 0.0) + amount));
        dirty.set(true);
    }

    public double getXp(UUID uuid) {
        return xp.getOrDefault(uuid, 0.0);
    }

    public int getLevel(UUID uuid) {
        var config = ConfigManager.salary();
        return LevelCurve.levelForXp(getXp(uuid), config.maxLevel, config.levelCurveBaseXp);
    }

    /**
     * @return true if the account had enough funds and the amount was deducted
     */
    public boolean take(UUID uuid, double amount) {
        double balance = getBalance(uuid);
        if (balance < amount) {
            return false;
        }
        setBalance(uuid, balance - amount);
        return true;
    }

    public boolean pay(UUID from, UUID to, double amount) {
        if (amount <= 0 || !take(from, amount)) {
            return false;
        }
        give(to, amount);
        return true;
    }

    public List<Map.Entry<UUID, Double>> top(int limit) {
        List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(balances.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return sorted.subList(0, Math.min(limit, sorted.size()));
    }
}
