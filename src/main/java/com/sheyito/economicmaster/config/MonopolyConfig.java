package com.sheyito.economicmaster.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Esquema de config/sheyitoscurrency/monopoly.json: define cada cuánto se sortea un evento
 * ({@link #eventsPerDay}) y la lista completa de eventos candidatos con su peso y parámetros.
 * La selección es ponderada (peso mayor = más probable) y el evento elegido permanece activo
 * hasta el siguiente sorteo.
 *
 * <p>WINDFALL (efecto instantáneo) ya está implementado: aplica al momento un efecto de poción
 * (elegido de la lista {@code effects}) a todos los jugadores conectados, con duración y
 * amplificador configurables por evento.
 */
public class MonopolyConfig {

    public boolean enabled = true;

    /** Eventos por día de juego (24000 ticks). 1 = un evento al día, 2 = dos al día. */
    public int eventsPerDay = 1;

    /** Apuesta mínima permitida en el cara o cruz (Sheyicoins). */
    public double minBet = 1.0;

    /** Apuesta máxima permitida; 0 = sin límite. */
    public double maxBet = 0.0;

    /** Lista de eventos candidatos para el sorteo ponderado. */
    public List<MonopolyEventEntry> events = new ArrayList<>(defaultEvents());

    public static MonopolyConfig defaults() {
        return new MonopolyConfig();
    }

    private static List<MonopolyEventEntry> defaultEvents() {
        List<MonopolyEventEntry> list = new ArrayList<>();

        list.add(new MonopolyEventEntry(
                "bonus_bancario", "SALARY_MULTIPLIER", true, 10,
                List.of(2.0), List.of(), 0.0, 0.05, 0.5,
                List.of(
                        "El banquero esta generoso: los salarios se multiplican por %multiplier%.",
                        "Dia de bonus: el proximo salario llega multiplicado por %multiplier%.")));

        list.add(new MonopolyEventEntry(
                "salario_sorpresa", "SALARY_MULTIPLIER", true, 8,
                List.of(0.5, 1.5, 2.0, 3.0), List.of(), 0.0, 0.05, 0.5,
                List.of(
                        "Ruleta de salarios: el proximo salario se multiplica por %multiplier%.",
                        "El banquero ha girado la ruleta: salario x%multiplier% la proxima vez.",
                        "Sorpresa salarial: tu proximo cobro se multiplica por %multiplier%.")));

        list.add(new MonopolyEventEntry(
                "fiebre_de_misiones", "QUEST_REWARD_MULTIPLIER", true, 10,
                List.of(2.0), List.of(), 0.0, 0.05, 0.5,
                List.of(
                        "Fiebre de misiones: las recompensas de quests se multiplican por %multiplier%.",
                        "Las quests pagan mas: recompensas x%multiplier% durante este evento.")));

        list.add(new MonopolyEventEntry(
                "misiones_sorpresa", "QUEST_REWARD_MULTIPLIER", true, 8,
                List.of(0.5, 1.5, 3.0), List.of(), 0.0, 0.05, 0.5,
                List.of(
                        "Misiones sorpresa: las recompensas se multiplican por %multiplier%.",
                        "Recompensas con descuento: x%multiplier% en tus quests.")));

        list.add(new MonopolyEventEntry(
                "mob_buscado", "MOB_WANTED", true, 10,
                List.of(), List.of("minecraft:zombie", "minecraft:skeleton", "minecraft:creeper",
                        "minecraft:enderman", "minecraft:wither_skeleton", "minecraft:blaze"), 25.0, 0.05, 0.5,
                List.of(
                        "Se busca: %mob%. Matarlo otorga %bounty% extra por cada uno.",
                        "Cazadores, a por %mob%: %bounty% extra por cada cabeza.",
                        "Se ofrece recompensa por %mob%: +%bounty% por kill.")));
        list.get(list.size() - 1).maxKills = 5;

        list.add(new MonopolyEventEntry(
                "cara_o_cruz", "HOUSE_COINFLIP", true, 5,
                List.of(), List.of(), 0.0, 0.05, 0.5,
                List.of(
                        "Cara o cruz contra La Casa: /monopoly coinflip <cantidad> [jugador]. La Casa cobra una comision del %commission%%.",
                        "La Casa abre mesa de cara o cruz: apuesta con /monopoly coinflip (comision %commission%%).")));

        list.add(new MonopolyEventEntry(
                "boss_buscado", "MOB_WANTED", true, 5,
                List.of(), List.of("minecraft:wither", "minecraft:ender_dragon",
                        "minecraft:elder_guardian", "minecraft:warden"), 500.0, 0.05, 0.5,
                List.of(
                        "¡Caceria de boss! Elimina a %mob% y reparte %bounty% Sheyicoins entre los cazadores.",
                        "Un boss ronda la zona: %mob%. Su derrota vale %bounty% Sheyicoins.",
                        "Se ofrece una fortuna por tumbar a %mob%: +%bounty% Sheyicoins en total.")));
        list.get(list.size() - 1).maxKills = 1;

        list.add(new MonopolyEventEntry(
                "golpe_de_suerte", "WINDFALL", true, 5,
                List.of(), List.of(), 0.0, 0.05, 0.5,
                List.of(
                        "¡Golpe de suerte! El banquero reparte %effect% a todos los jugadores durante %duration%s.",
                        "Dia de wellness: todos los jugadores reciben %effect% durante %duration%s.")));
        list.get(list.size() - 1).effects = List.of(
                "minecraft:regeneration", "minecraft:speed", "minecraft:haste",
                "minecraft:luck", "minecraft:absorption", "minecraft:resistance",
                "minecraft:night_vision");
        list.get(list.size() - 1).effectDurationSeconds = 60;
        list.get(list.size() - 1).effectAmplifier = 0;

        return list;
    }
}
