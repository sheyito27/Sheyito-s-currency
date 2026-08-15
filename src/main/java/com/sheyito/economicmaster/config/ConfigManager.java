package com.sheyito.economicmaster.config;

import com.sheyito.economicmaster.EconomicMaster;
import com.sheyito.economicmaster.util.JsonFileUtil;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/**
 * Loads and holds every config/sheyitoscurrency/*.json file in memory. All fields are
 * auto-generated with sane defaults the first time the server starts.
 */
public final class ConfigManager {

    private static volatile GeneralConfig general;
    private static volatile MobRewardsConfig mobRewards;
    private static volatile SalaryConfig salary;
    private static volatile QuestRewardsConfig questRewards;
    private static volatile SubscriptionsConfig subscriptions;
    private static volatile ShopConfig shop;
    private static volatile XpShopConfig xpShop;
    private static volatile DebtConfig debt;
    private static volatile WaystoneTollConfig waystoneToll;
    private static volatile DimensionUnlockConfig dimensionUnlock;
    private static volatile ChunkClaimConfig chunkClaim;
    private static volatile TransmissionTaxConfig transmissionTax;
    private static volatile EmbargoConfig embargo;

    private ConfigManager() {
    }

    /**
     * Resolved lazily (not as a static field) so merely loading the {@code ConfigManager}
     * class - including reflectively, e.g. to mock it in tests - never touches FML.
     */
    private static Path configDir() {
        return FMLPaths.CONFIGDIR.get().resolve(EconomicMaster.MODID);
    }

    public static synchronized void load() {
        Path CONFIG_DIR = configDir();
        general = JsonFileUtil.loadOrCreate(CONFIG_DIR.resolve("general.json"), GeneralConfig.class, GeneralConfig::defaults);
        mobRewards = JsonFileUtil.loadOrCreate(CONFIG_DIR.resolve("mobs.json"), MobRewardsConfig.class, MobRewardsConfig::defaults);
        salary = JsonFileUtil.loadOrCreate(CONFIG_DIR.resolve("salary.json"), SalaryConfig.class, SalaryConfig::defaults);
        questRewards = JsonFileUtil.loadOrCreate(CONFIG_DIR.resolve("quests_rewards.json"), QuestRewardsConfig.class, QuestRewardsConfig::defaults);
        subscriptions = JsonFileUtil.loadOrCreate(CONFIG_DIR.resolve("subscriptions.json"), SubscriptionsConfig.class, SubscriptionsConfig::defaults);
        shop = JsonFileUtil.loadOrCreate(CONFIG_DIR.resolve("shop.json"), ShopConfig.class, ShopConfig::defaults);
        xpShop = JsonFileUtil.loadOrCreate(CONFIG_DIR.resolve("xp_shop.json"), XpShopConfig.class, XpShopConfig::defaults);
        debt = JsonFileUtil.loadOrCreate(CONFIG_DIR.resolve("debt.json"), DebtConfig.class, DebtConfig::defaults);
        waystoneToll = JsonFileUtil.loadOrCreate(CONFIG_DIR.resolve("waystone_toll.json"), WaystoneTollConfig.class, WaystoneTollConfig::defaults);
        dimensionUnlock = JsonFileUtil.loadOrCreate(CONFIG_DIR.resolve("dimension_unlock.json"), DimensionUnlockConfig.class, DimensionUnlockConfig::defaults);
        chunkClaim = JsonFileUtil.loadOrCreate(CONFIG_DIR.resolve("chunk_claim.json"), ChunkClaimConfig.class, ChunkClaimConfig::defaults);
        transmissionTax = JsonFileUtil.loadOrCreate(CONFIG_DIR.resolve("transmission_tax.json"), TransmissionTaxConfig.class, TransmissionTaxConfig::defaults);
        embargo = JsonFileUtil.loadOrCreate(CONFIG_DIR.resolve("embargo.json"), EmbargoConfig.class, EmbargoConfig::defaults);
        EconomicMaster.LOGGER.info("Sheyito's currency: configuracion cargada desde {}", CONFIG_DIR);
    }

    public static GeneralConfig general() {
        return general;
    }

    public static MobRewardsConfig mobRewards() {
        return mobRewards;
    }

    public static SalaryConfig salary() {
        return salary;
    }

    public static QuestRewardsConfig questRewards() {
        return questRewards;
    }

    public static SubscriptionsConfig subscriptions() {
        return subscriptions;
    }

    public static ShopConfig shop() {
        return shop;
    }

    public static XpShopConfig xpShop() {
        return xpShop;
    }

    public static DebtConfig debt() {
        return debt;
    }

    public static WaystoneTollConfig waystoneToll() {
        return waystoneToll;
    }

    public static DimensionUnlockConfig dimensionUnlock() {
        return dimensionUnlock;
    }

    public static ChunkClaimConfig chunkClaim() {
        return chunkClaim;
    }

    public static TransmissionTaxConfig transmissionTax() {
        return transmissionTax;
    }

    public static EmbargoConfig embargo() {
        return embargo;
    }
}
