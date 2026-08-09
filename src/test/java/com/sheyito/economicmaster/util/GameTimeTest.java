package com.sheyito.economicmaster.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Backs the day-boundary checks behind salary payouts and subscription billing. */
class GameTimeTest {

    @Test
    void currentDayDividesGameTimeByTicksPerDay() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel overworld = mock(ServerLevel.class);
        when(server.overworld()).thenReturn(overworld);
        when(overworld.getGameTime()).thenReturn(48001L);

        assertEquals(2, GameTime.currentDay(server));
    }

    @Test
    void currentDayIsZeroBeforeFirstFullDay() {
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel overworld = mock(ServerLevel.class);
        when(server.overworld()).thenReturn(overworld);
        when(overworld.getGameTime()).thenReturn(23_999L);

        assertEquals(0, GameTime.currentDay(server));
    }
}
