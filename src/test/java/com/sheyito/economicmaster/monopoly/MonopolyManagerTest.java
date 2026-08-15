package com.sheyito.economicmaster.monopoly;

import com.mojang.authlib.GameProfile;
import com.sheyito.economicmaster.TestBootstrap;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.GeneralConfig;
import com.sheyito.economicmaster.config.MonopolyConfig;
import com.sheyito.economicmaster.config.MonopolyEventEntry;
import com.sheyito.economicmaster.economy.EconomyManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cubre el sorteo ponderado de eventos económicos (Monopoly): cadencia por periodo, elección de
 * multiplicador/mob desde las listas configuradas, y el subsistema de cara o cruz contra La Casa
 * y entre jugadores con comisión.
 */
class MonopolyManagerTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    private interface WithMonopoly {
        void run(MonopolyManager monopoly, EconomyManager economy, MinecraftServer server) throws Exception;
    }

    private static void withMonopoly(MonopolyConfig config, long gameTime, WithMonopoly test) throws Exception {
        GeneralConfig general = new GeneralConfig();
        general.decimals = 2;

        EconomyManager economy = EconomyManager.createForTesting();
        EconomyManager.installForTesting(economy);

        MonopolyManager monopoly = MonopolyManager.createForTesting();
        MonopolyManager.installForTesting(monopoly);

        MinecraftServer server = mock(MinecraftServer.class);
        PlayerList playerList = mock(PlayerList.class);
        ServerLevel overworld = mock(ServerLevel.class);
        when(server.getPlayerList()).thenReturn(playerList);
        when(server.overworld()).thenReturn(overworld);
        when(overworld.getGameTime()).thenReturn(gameTime);
        when(server.getTickCount()).thenReturn(0);

        try (MockedStatic<ConfigManager> mocked = mockStatic(ConfigManager.class)) {
            mocked.when(ConfigManager::general).thenReturn(general);
            mocked.when(ConfigManager::monopoly).thenReturn(config);
            test.run(monopoly, economy, server);
        } finally {
            EconomyManager.installForTesting(null);
            MonopolyManager.installForTesting(null);
        }
    }

    private static MonopolyConfig configWith(MonopolyEventEntry... entries) {
        MonopolyConfig config = new MonopolyConfig();
        config.enabled = true;
        config.eventsPerDay = 1;
        config.minBet = 1.0;
        config.events.clear();
        config.events.addAll(List.of(entries));
        return config;
    }

    private static MonopolyEventEntry salaryEvent(String id, double weight, List<Double> multipliers) {
        return new MonopolyEventEntry(id, "SALARY_MULTIPLIER", true, weight, multipliers, List.of(), 0.0, 0.05, 0.5, List.of());
    }

    private static MonopolyEventEntry questEvent(String id, List<Double> multipliers) {
        return new MonopolyEventEntry(id, "QUEST_REWARD_MULTIPLIER", true, 10, multipliers, List.of(), 0.0, 0.05, 0.5, List.of());
    }

    private static MonopolyEventEntry coinflipEvent() {
        return new MonopolyEventEntry("cara_o_cruz", "HOUSE_COINFLIP", true, 5, List.of(), List.of(), 0.0, 0.05, 0.5, List.of());
    }

    private static MonopolyEventEntry mobEvent(String id, List<String> mobs, double bounty) {
        return new MonopolyEventEntry(id, "MOB_WANTED", true, 10, List.of(), mobs, bounty, 0.05, 0.5, List.of());
    }

    private static MonopolyEventEntry mobEvent(String id, List<String> mobs, double bounty, int maxKills) {
        MonopolyEventEntry entry = mobEvent(id, mobs, bounty);
        entry.maxKills = maxKills;
        return entry;
    }

    @Test
    void noActiveEventReturnsNeutralMultipliers() throws Exception {
        MonopolyConfig config = new MonopolyConfig();
        config.enabled = true;
        config.eventsPerDay = 1;
        withMonopoly(config, 5 * 24000L, (monopoly, economy, server) -> {
            assertFalse(monopoly.isActive());
            assertEquals(1.0, monopoly.salaryMultiplier());
            assertEquals(1.0, monopoly.questRewardMultiplier());
            assertFalse(monopoly.isMobWanted());
            assertFalse(monopoly.isCoinflipActive());
        });
    }

    @Test
    void freshServerRollsImmediatelyOnFirstTick() throws Exception {
        MonopolyConfig config = configWith(salaryEvent("bonus", 10.0, List.of(2.0)));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.tick(server);

            assertTrue(monopoly.isActive());
            assertEquals("bonus", monopoly.currentEventId());
            assertEquals(2.0, monopoly.salaryMultiplier());
        });
    }

    @Test
    void tickRollsOnlyOncePerPeriod() throws Exception {
        MonopolyConfig config = configWith(salaryEvent("bonus", 10.0, List.of(2.0)));
        withMonopoly(config, 5 * 24000L, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);

            monopoly.tick(server);
            assertEquals(5L, monopoly.lastProcessedPeriod());
            assertTrue(monopoly.isActive());

            monopoly.tick(server);
            assertEquals(5L, monopoly.lastProcessedPeriod(), "mismo periodo no debe re-sortear");

            when(server.overworld().getGameTime()).thenReturn(6L * 24000L);
            monopoly.tick(server);
            assertEquals(6L, monopoly.lastProcessedPeriod(), "periodo nuevo debe sortear de nuevo");
            assertTrue(monopoly.isActive());
        });
    }

    @Test
    void salaryMultiplierIsPickedFromConfiguredList() throws Exception {
        MonopolyConfig config = configWith(salaryEvent("ruleta", 10.0, List.of(2.0, 3.0)));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.forceRoll(server, "ruleta");
            assertEquals(2.0, monopoly.salaryMultiplier());

            monopoly.setRng(() -> 0.99);
            monopoly.forceRoll(server, "ruleta");
            assertEquals(3.0, monopoly.salaryMultiplier());
        });
    }

    @Test
    void messageIsPickedRandomlyFromConfiguredList() throws Exception {
        MonopolyEventEntry entry = new MonopolyEventEntry("ruleta", "SALARY_MULTIPLIER", true, 10,
                List.of(2.0), List.of(), 0.0, 0.05, 0.5,
                List.of("Mensaje A: salarios x%multiplier%.", "Mensaje B: salarios x%multiplier%."));
        MonopolyConfig config = configWith(entry);
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.forceRoll(server, "ruleta");
            assertEquals("Mensaje A: salarios x%multiplier%.", monopoly.currentMessage());

            monopoly.setRng(() -> 0.99);
            monopoly.forceRoll(server, "ruleta");
            assertEquals("Mensaje B: salarios x%multiplier%.", monopoly.currentMessage());
        });
    }

    @Test
    void messageFallsBackToDefaultWhenListIsEmpty() throws Exception {
        MonopolyConfig config = configWith(salaryEvent("bonus", 10.0, List.of(2.0)));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.forceRoll(server, "bonus");
            assertNull(monopoly.currentMessage(), "sin mensajes configurados el evento usa el default");
        });
    }

    @Test
    void broadcastSendsTheSortedMessageWithTokensReplaced() throws Exception {
        MonopolyEventEntry entry = new MonopolyEventEntry("ruleta", "SALARY_MULTIPLIER", true, 10,
                List.of(2.0), List.of(), 0.0, 0.05, 0.5,
                List.of("Ganador: los salarios suben a %multiplier%!"));
        MonopolyConfig config = configWith(entry);
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            ServerPlayer sp = mock(ServerPlayer.class);
            when(server.getPlayerList().getPlayers()).thenReturn(List.of(sp));

            monopoly.setRng(() -> 0.0);
            monopoly.forceRoll(server, "ruleta");

            ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
            verify(sp).sendSystemMessage(captor.capture());
            String sent = captor.getValue().getString();
            assertTrue(sent.contains("Ganador: los salarios suben a x2.00!"), "broadcast usa el mensaje sorteado y sustituye tokens: " + sent);
        });
    }

    @Test
    void questRewardMultiplierIsPickedFromConfiguredList() throws Exception {
        MonopolyConfig config = configWith(questEvent("misiones", List.of(2.0, 4.0)));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.forceRoll(server, "misiones");
            assertEquals(2.0, monopoly.questRewardMultiplier());
            assertEquals(1.0, monopoly.salaryMultiplier(), "el evento de misiones no afecta al salario");
        });
    }

    @Test
    void mobWantedPicksMobFromListAndBounty() throws Exception {
        MonopolyEventEntry entry = new MonopolyEventEntry("se_busca", "MOB_WANTED", true, 10,
                List.of(), List.of("minecraft:zombie", "minecraft:skeleton"), 25.0, 0.05, 0.5, List.of());
        MonopolyConfig config = configWith(entry);
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.forceRoll(server, "se_busca");

            assertTrue(monopoly.isMobWanted());
            assertEquals("minecraft:zombie", monopoly.wantedMob());
            assertEquals(25.0, monopoly.wantedBounty());
            assertFalse(monopoly.isCoinflipActive());
        });
    }

    @Test
    void mobWantedCapStopsPayoutWhenExhaustedWithoutEndingEvent() throws Exception {
        MonopolyConfig config = configWith(mobEvent("se_busca", List.of("minecraft:zombie"), 25.0, 5));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.forceRoll(server, "se_busca");
            assertTrue(monopoly.mobWantedPayoutActive());

            for (int i = 1; i <= 4; i++) {
                monopoly.onMobWantedKilled(server);
                assertTrue(monopoly.mobWantedPayoutActive(), "antes del cupo se sigue pagando");
                assertEquals(i, monopoly.currentMobKills());
            }

            monopoly.onMobWantedKilled(server);
            assertFalse(monopoly.mobWantedPayoutActive(), "al agotar el cupo deja de pagar");
            assertTrue(monopoly.mobBountyExhausted());
            assertTrue(monopoly.isActive(), "el evento no termina al agotarse el cupo");
            assertTrue(monopoly.isMobWanted());
            assertEquals(5, monopoly.currentMobKills());
        });
    }

    @Test
    void mobWantedCapZeroMeansUnlimited() throws Exception {
        MonopolyConfig config = configWith(mobEvent("se_busca", List.of("minecraft:zombie"), 25.0, 0));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.forceRoll(server, "se_busca");

            for (int i = 0; i < 100; i++) {
                monopoly.onMobWantedKilled(server);
            }
            assertFalse(monopoly.mobBountyExhausted(), "maxKills 0 = sin limite");
            assertTrue(monopoly.mobWantedPayoutActive());
            assertEquals(100, monopoly.currentMobKills());
        });
    }

    @Test
    void mobWantedKillsResetOnNewRoll() throws Exception {
        MonopolyConfig config = configWith(mobEvent("se_busca", List.of("minecraft:zombie"), 25.0, 5));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.forceRoll(server, "se_busca");
            monopoly.onMobWantedKilled(server);
            monopoly.onMobWantedKilled(server);
            monopoly.onMobWantedKilled(server);
            assertEquals(3, monopoly.currentMobKills());

            monopoly.forceRoll(server, "se_busca");
            assertEquals(0, monopoly.currentMobKills(), "un re-sorteo reinicia el cupo");
            assertTrue(monopoly.mobWantedPayoutActive());
        });
    }

    @Test
    void weightedRollRespectsWeights() throws Exception {
        MonopolyConfig config = configWith(
                salaryEvent("pesado", 10.0, List.of(2.0)),
                salaryEvent("ligero", 1.0, List.of(3.0)));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.roll(server, null);
            assertEquals("pesado", monopoly.currentEventId());

            monopoly.setRng(() -> 0.99);
            monopoly.roll(server, null);
            assertEquals("ligero", monopoly.currentEventId());
        });
    }

    @Test
    void invalidEntriesAreIgnoredByTheRoll() throws Exception {
        MonopolyEventEntry broken = new MonopolyEventEntry("roto", "SALARY_MULTIPLIER", true, 10,
                List.of(), List.of(), 0.0, 0.05, 0.5, List.of());
        MonopolyConfig config = configWith(broken);
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.tick(server);
            assertFalse(monopoly.isActive(), "entradas sin multiplicadores no deben sortearse");
        });
    }

    @Test
    void disabledConfigClearsActiveEvent() throws Exception {
        MonopolyConfig config = configWith(salaryEvent("bonus", 10.0, List.of(2.0)));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.tick(server);
            assertTrue(monopoly.isActive());

            config.enabled = false;
            monopoly.tick(server);
            assertFalse(monopoly.isActive());
            assertEquals(1.0, monopoly.salaryMultiplier());
        });
    }

    @Test
    void coinflipVsHouseWinAndLoseWithForcedRng() throws Exception {
        MonopolyConfig config = configWith(coinflipEvent());
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.forceRoll(server, "cara_o_cruz");
            assertTrue(monopoly.isCoinflipActive());

            UUID player = UUID.randomUUID();
            ServerPlayer sp = mock(ServerPlayer.class);
            when(sp.getUUID()).thenReturn(player);
            economy.give(player, 1000.0);

            monopoly.setRng(() -> 0.2);
            monopoly.coinflipVsHouse(server, sp, 100.0);
            assertEquals(1095.0, economy.getBalance(player), "ganar: 1000 - 105 (comision) + 200 = 1095");

            monopoly.setRng(() -> 0.8);
            monopoly.coinflipVsHouse(server, sp, 100.0);
            assertEquals(990.0, economy.getBalance(player), "perder: 1095 - 105 = 990");
        });
    }

    @Test
    void p2pCoinflipChargesBothAndPaysWinnerOnce() throws Exception {
        MonopolyConfig config = configWith(coinflipEvent());
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.forceRoll(server, "cara_o_cruz");

            UUID challengerId = UUID.randomUUID();
            UUID acceptorId = UUID.randomUUID();
            ServerPlayer challenger = mock(ServerPlayer.class);
            when(challenger.getUUID()).thenReturn(challengerId);
            when(challenger.getGameProfile()).thenReturn(new GameProfile(challengerId, "Retador"));
            ServerPlayer acceptor = mock(ServerPlayer.class);
            when(acceptor.getUUID()).thenReturn(acceptorId);
            when(acceptor.getGameProfile()).thenReturn(new GameProfile(acceptorId, "Aceptador"));
            when(server.getPlayerList().getPlayer(challengerId)).thenReturn(challenger);
            when(server.getPlayerList().getPlayer(acceptorId)).thenReturn(acceptor);

            economy.give(challengerId, 500.0);
            economy.give(acceptorId, 500.0);

            monopoly.setRng(() -> 0.0);
            monopoly.inviteCoinflip(server, challenger, acceptor, 100.0);

            monopoly.setRng(() -> 0.99);
            assertTrue(monopoly.acceptCoinflip(server, acceptor), "el retado gana con rng >= 0.5");

            assertEquals(395.0, economy.getBalance(challengerId), "retador: 500 - 105 = 395");
            assertEquals(595.0, economy.getBalance(acceptorId), "retado: 500 - 105 + 200 = 595");

            assertFalse(monopoly.acceptCoinflip(server, acceptor), "la invitacion se consume tras aceptar");
        });
    }

    @Test
    void p2pCoinflipFailsIfInviteWasDenied() throws Exception {
        MonopolyConfig config = configWith(coinflipEvent());
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.forceRoll(server, "cara_o_cruz");

            UUID challengerId = UUID.randomUUID();
            UUID acceptorId = UUID.randomUUID();
            ServerPlayer challenger = mock(ServerPlayer.class);
            when(challenger.getUUID()).thenReturn(challengerId);
            when(challenger.getGameProfile()).thenReturn(new GameProfile(challengerId, "Retador"));
            ServerPlayer acceptor = mock(ServerPlayer.class);
            when(acceptor.getUUID()).thenReturn(acceptorId);
            when(acceptor.getGameProfile()).thenReturn(new GameProfile(acceptorId, "Aceptador"));
            when(server.getPlayerList().getPlayer(challengerId)).thenReturn(challenger);

            economy.give(challengerId, 500.0);
            economy.give(acceptorId, 500.0);

            monopoly.inviteCoinflip(server, challenger, acceptor, 100.0);
            assertTrue(monopoly.denyCoinflip(server, acceptor));
            assertFalse(monopoly.acceptCoinflip(server, acceptor), "aceptar tras rechazar no debe ejecutar nada");

            assertEquals(500.0, economy.getBalance(challengerId), "nadie paga si se rechaza");
            assertEquals(500.0, economy.getBalance(acceptorId));
        });
    }

    @Test
    void damageContributorsAreTrackedPerMobAndDeduplicated() throws Exception {
        MonopolyConfig config = configWith(mobEvent("se_busca", List.of("minecraft:zombie"), 25.0));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            UUID mob = UUID.randomUUID();
            UUID ana = UUID.randomUUID();
            UUID beto = UUID.randomUUID();

            monopoly.recordDamage(mob, ana, "Ana", 100);
            monopoly.recordDamage(mob, ana, "Ana", 150);
            monopoly.recordDamage(mob, beto, "Beto", 160);

            Map<UUID, String> contributors = monopoly.contributorNames(mob);
            assertEquals(2, contributors.size(), "el mismo jugador solo cuenta una vez");
            assertEquals("Ana", contributors.get(ana));
            assertEquals("Beto", contributors.get(beto));
            assertTrue(monopoly.contributorNames(UUID.randomUUID()).isEmpty(), "mobs sin daño no tienen contribuidores");
        });
    }

    @Test
    void staleContributorsArePrunedAfterTtl() throws Exception {
        MonopolyConfig config = configWith(mobEvent("se_busca", List.of("minecraft:zombie"), 25.0));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            UUID mob = UUID.randomUUID();
            UUID ana = UUID.randomUUID();
            monopoly.recordDamage(mob, ana, "Ana", 100);

            monopoly.pruneStaleContributors(100 + 20 * 60);
            assertEquals(1, monopoly.contributorNames(mob).size(), "dentro de la ventana TTL se conserva");

            monopoly.pruneStaleContributors(100 + 20 * 60 + 1);
            assertTrue(monopoly.contributorNames(mob).isEmpty(), "pasada la ventana TTL se olvida");
        });
    }

    @Test
    void forgettingContributorsClearsTheRecord() throws Exception {
        MonopolyConfig config = configWith(mobEvent("se_busca", List.of("minecraft:zombie"), 25.0));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            UUID mob = UUID.randomUUID();
            monopoly.recordDamage(mob, UUID.randomUUID(), "Ana", 100);
            assertFalse(monopoly.contributorNames(mob).isEmpty());

            monopoly.forgetContributors(mob);
            assertTrue(monopoly.contributorNames(mob).isEmpty());
        });
    }

    @Test
    void bountyShareSplitsEquallyAmongContributors() throws Exception {
        MonopolyConfig config = configWith(mobEvent("se_busca", List.of("minecraft:zombie"), 25.0));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            assertEquals(33.33, MonopolyManager.bountyShare(100.0, 3), 0.001, "resto de céntimos no se acuña");
            assertEquals(50.0, MonopolyManager.bountyShare(100.0, 2), 0.001);
            assertEquals(100.0, MonopolyManager.bountyShare(100.0, 1), 0.001);
            assertEquals(0.0, MonopolyManager.bountyShare(100.0, 0), 0.001);
        });
    }

    @Test
    void endingTheEventClearsTrackedContributors() throws Exception {
        MonopolyConfig config = configWith(mobEvent("se_busca", List.of("minecraft:zombie"), 25.0));
        withMonopoly(config, 0, (monopoly, economy, server) -> {
            monopoly.setRng(() -> 0.0);
            monopoly.forceRoll(server, "se_busca");

            UUID mob = UUID.randomUUID();
            monopoly.recordDamage(mob, UUID.randomUUID(), "Ana", 100);
            assertFalse(monopoly.contributorNames(mob).isEmpty());

            monopoly.endNow(server);
            assertTrue(monopoly.contributorNames(mob).isEmpty(), "al terminar el evento no quedan contribuidores huérfanos");
        });
    }
}
