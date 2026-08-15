package com.sheyito.economicmaster.commands;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public class CommandRegistrar {

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        BalCommand.register(event.getDispatcher());
        BalTopCommand.register(event.getDispatcher());
        PayCommand.register(event.getDispatcher());
        SubscribeCommand.register(event.getDispatcher());
        EcoCommand.register(event.getDispatcher());
        EconomicMasterCommand.register(event.getDispatcher());
        TradeCommand.register(event.getDispatcher());
        BuyCommand.register(event.getDispatcher());
        DimensionCommand.register(event.getDispatcher());
        ChunkCommand.register(event.getDispatcher());
        EmbargoCommand.register(event.getDispatcher());
    }
}
