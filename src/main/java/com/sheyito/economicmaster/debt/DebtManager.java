package com.sheyito.economicmaster.debt;

import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.data.DebtData;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.GameTime;
import com.sheyito.economicmaster.util.JsonFileUtil;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks the repayment deadline for players who died with a low enough balance to be charged
 * a flat death penalty that pushed them into debt (see {@code PlayerDeathDebtListener}). The
 * debt amount itself is never duplicated here - it's just {@code -EconomyManager.getBalance()}
 * whenever that's negative - this manager only owns the "how many in-game days are left" clock.
 *
 * <p>Enforcement of an overdue debt (embargo, seizure, ...) is intentionally out of scope here;
 * {@link #isOverdue} is the seam a future feature would poll.
 */
public class DebtManager {

    private static volatile DebtManager instance;

    private final Path file;
    private final Map<UUID, Long> dueGameDay = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private DebtManager(Path file) {
        this.file = file;
    }

    public static void init(MinecraftServer server) {
        DebtManager manager = new DebtManager(DataPaths.dataDir(server).resolve("debt_data.json"));
        manager.load();
        instance = manager;
    }

    public static DebtManager get() {
        return instance;
    }

    /** Test-support seam, not part of the mod's real lifecycle - production code must always
     * go through {@link #init(MinecraftServer)}. Builds an in-memory instance that never
     * touches disk (no load(), no save() called). */
    public static DebtManager createForTesting() {
        return new DebtManager(Path.of("build", "test-tmp", "unused-debt-test-file.json"));
    }

    /** Test-support seam: installs {@code manager} as the active singleton {@link #get()} returns. */
    public static void installForTesting(DebtManager manager) {
        instance = manager;
    }

    public static void shutdown() {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    private void load() {
        DebtData data = JsonFileUtil.loadOrCreate(file, DebtData.class, DebtData::empty);
        data.dueDay.forEach((uuid, day) -> dueGameDay.put(UUID.fromString(uuid), day));
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        DebtData data = new DebtData();
        dueGameDay.forEach((uuid, day) -> data.dueDay.put(uuid.toString(), day));
        JsonFileUtil.save(file, data);
    }

    /** Registers/renews the repayment deadline for a player who just went into debt. */
    public void incurDebt(UUID uuid, long dueGameDay) {
        this.dueGameDay.put(uuid, dueGameDay);
        dirty.set(true);
    }

    public Optional<Long> getDueGameDay(UUID uuid) {
        pruneIfRepaid(uuid);
        return Optional.ofNullable(dueGameDay.get(uuid));
    }

    /** Amount currently owed, or 0.0 if the player isn't in debt. */
    public double getDebtAmount(UUID uuid) {
        double balance = EconomyManager.get().getBalance(uuid);
        return balance < 0 ? -balance : 0.0;
    }

    /** True once a registered deadline has passed and the balance is still negative. */
    public boolean isOverdue(MinecraftServer server, UUID uuid) {
        pruneIfRepaid(uuid);
        Long due = dueGameDay.get(uuid);
        return due != null && GameTime.currentDay(server) >= due;
    }

    /** A debt clears itself the moment the balance is back to non-negative - no need to wait for embargo logic. */
    private void pruneIfRepaid(UUID uuid) {
        if (dueGameDay.containsKey(uuid) && EconomyManager.get().getBalance(uuid) >= 0) {
            dueGameDay.remove(uuid);
            dirty.set(true);
        }
    }
}
