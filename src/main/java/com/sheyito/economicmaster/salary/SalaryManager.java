package com.sheyito.economicmaster.salary;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.SalaryConfig;
import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.data.SalaryData;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.monopoly.MonopolyManager;
import com.sheyito.economicmaster.util.GameTime;
import com.sheyito.economicmaster.util.JsonFileUtil;
import com.sheyito.economicmaster.util.LevelCurve;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pays connected players a daily salary every {@code intervalGameDays} in-game days (tracked
 * off {@link GameTime}, so offline time never counts and there is nothing to "catch up" on).
 * The amount scales with the player's level via {@link LevelCurve}.
 */
public class SalaryManager {

    private static volatile SalaryManager instance;

    private final Path file;
    private final Map<UUID, Long> lastPayoutDay = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private SalaryManager(Path file) {
        this.file = file;
    }

    public static void init(MinecraftServer server) {
        SalaryManager manager = new SalaryManager(DataPaths.dataDir(server).resolve("salary_data.json"));
        manager.load();
        instance = manager;
    }

    public static SalaryManager get() {
        return instance;
    }

    /** Test-only: an in-memory instance that never touches disk (no load(), no save() called). */
    static SalaryManager createForTesting() {
        return new SalaryManager(Path.of("build", "test-tmp", "unused-salary-test-file.json"));
    }

    public static void shutdown() {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    private void load() {
        SalaryData data = JsonFileUtil.loadOrCreate(file, SalaryData.class, SalaryData::empty);
        data.lastPayout.forEach((uuid, day) -> lastPayoutDay.put(UUID.fromString(uuid), day));
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        SalaryData data = new SalaryData();
        lastPayoutDay.forEach((uuid, day) -> data.lastPayout.put(uuid.toString(), day));
        JsonFileUtil.save(file, data);
    }

    public void tick(MinecraftServer server) {
        SalaryConfig config = ConfigManager.salary();
        if (!config.enabled) {
            return;
        }
        long currentDay = GameTime.currentDay(server);
        int intervalDays = Math.max(1, config.intervalGameDays);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID uuid = player.getUUID();
            Long last = lastPayoutDay.get(uuid);
            if (last == null) {
                lastPayoutDay.put(uuid, currentDay);
                dirty.set(true);
                continue;
            }
            if (currentDay - last >= intervalDays) {
                EconomyManager economy = EconomyManager.get();
                int level = economy.getLevel(uuid);
                double amount = LevelCurve.salaryForLevel(level, config.baseSalary, config.maxSalary, config.maxLevel);
                double multiplier = MonopolyManager.get() == null ? 1.0 : MonopolyManager.get().salaryMultiplier();
                amount = Money.round(amount * multiplier);

                economy.giveEarned(uuid, amount);
                lastPayoutDay.put(uuid, currentDay);
                dirty.set(true);

                String eventNote = multiplier != 1.0 ? " §6(x" + String.format(Locale.US, "%.2f", multiplier) + " por evento)" : "";
                player.sendSystemMessage(Component.literal("§a[Sheyito's currency] §fCobraste tu salario del dia " + currentDay + ": §e" + Money.format(amount) + " §f(nivel " + level + ")." + eventNote));
            }
        }
    }
}
