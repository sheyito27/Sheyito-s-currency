package com.sheyito.economicmaster.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Esquema de config/sheyitoscurrency/monopoly.json: define cada cuánto se sortea un evento
 * ({@link #eventsPerDay}) y la lista completa de eventos candidatos con su peso y parámetros.
 * La selección es ponderada (peso mayor = más probable) y el evento elegido permanece activo
 * hasta el siguiente sorteo.
 *
 * <p>WINDFALL (lluvia de dinero) está planeado pero todavía no implementado: no hay ninguna
 * entrada de ese tipo en los defaults y el {@code EventType} no lo expone aún.
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
                "El banquero esta generoso: los salarios se multiplican por %multiplier%."));

        list.add(new MonopolyEventEntry(
                "salario_sorpresa", "SALARY_MULTIPLIER", true, 8,
                List.of(0.5, 1.5, 2.0, 3.0), List.of(), 0.0, 0.05, 0.5,
                "Ruleta de salarios: el proximo salario se multiplica por %multiplier%."));

        list.add(new MonopolyEventEntry(
                "fiebre_de_misiones", "QUEST_REWARD_MULTIPLIER", true, 10,
                List.of(2.0), List.of(), 0.0, 0.05, 0.5,
                "Fiebre de misiones: las recompensas de quests se multiplican por %multiplier%."));

        list.add(new MonopolyEventEntry(
                "misiones_sorpresa", "QUEST_REWARD_MULTIPLIER", true, 8,
                List.of(0.5, 1.5, 3.0), List.of(), 0.0, 0.05, 0.5,
                "Misiones sorpresa: las recompensas se multiplican por %multiplier%."));

        list.add(new MonopolyEventEntry(
                "mob_buscado", "MOB_WANTED", true, 10,
                List.of(), List.of("minecraft:zombie", "minecraft:skeleton", "minecraft:creeper",
                        "minecraft:enderman", "minecraft:wither_skeleton", "minecraft:blaze"), 25.0, 0.05, 0.5,
                "Se busca: %mob%. Matarlo otorga %bounty% extra por cada uno."));

        list.add(new MonopolyEventEntry(
                "cara_o_cruz", "HOUSE_COINFLIP", true, 5,
                List.of(), List.of(), 0.0, 0.05, 0.5,
                "Cara o cruz contra La Casa: /monopoly coinflip <cantidad> [jugador]. La Casa cobra una comision del %commission%%."));

        return list;
    }
}
