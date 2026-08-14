package com.sheyito.economicmaster;

import com.mojang.logging.LogUtils;
import com.sheyito.economicmaster.commands.CommandRegistrar;
import com.sheyito.economicmaster.events.MobKillListener;
import com.sheyito.economicmaster.events.PlayerDeathDebtListener;
import com.sheyito.economicmaster.events.ServerLifecycleHandler;
import com.sheyito.economicmaster.integration.FTBQuestsCompat;
import com.sheyito.economicmaster.scheduler.EconomicMasterScheduler;
import com.sheyito.economicmaster.shop.ShopCreationTracker;
import com.sheyito.economicmaster.shop.ShopProtectionListener;
import com.sheyito.economicmaster.shop.ShopTradeListener;
import com.sheyito.economicmaster.trade.TradeScheduler;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * EconomicMaster - server-side virtual economy mod with FTB Quests reward integration.
 * No client-side registrations exist anywhere in this mod; it is safe to run on
 * dedicated servers only.
 */
@Mod(EconomicMaster.MODID)
public class EconomicMaster {

    public static final String MODID = "sheyitoscurrency";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EconomicMaster(IEventBus modEventBus, ModContainer modContainer) {
        NeoForge.EVENT_BUS.register(new ServerLifecycleHandler());
        NeoForge.EVENT_BUS.register(new MobKillListener());
        NeoForge.EVENT_BUS.register(new PlayerDeathDebtListener());
        NeoForge.EVENT_BUS.register(new EconomicMasterScheduler());
        NeoForge.EVENT_BUS.register(new TradeScheduler());
        NeoForge.EVENT_BUS.register(new CommandRegistrar());
        NeoForge.EVENT_BUS.register(new ShopCreationTracker());
        NeoForge.EVENT_BUS.register(new ShopTradeListener());
        NeoForge.EVENT_BUS.register(new ShopProtectionListener());

        modEventBus.addListener((FMLCommonSetupEvent event) -> FTBQuestsCompat.logDetection());

        LOGGER.info("Sheyito's currency inicializado (server-side).");
    }
}
