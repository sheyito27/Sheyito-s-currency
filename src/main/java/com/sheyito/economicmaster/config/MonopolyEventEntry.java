package com.sheyito.economicmaster.config;

import java.util.List;

/**
 * Una entrada de la lista de eventos de monopoly.json. Todos los campos son públicos y
 * opcionales según el {@code type}: cada evento solo lee los campos que le corresponden, así
 * que el JSON es una lista plana de objetos con los mismos campos (los que no apliquen se
 * ignoran o usan su valor por defecto).
 *
 * <p>Tipos soportados (ver {@link com.sheyito.economicmaster.monopoly.EventType}):
 * <ul>
 *   <li>{@code SALARY_MULTIPLIER} / {@code QUEST_REWARD_MULTIPLIER}: usan {@link #multipliers}
 *       — el multiplicador activo se elige al azar de esa lista en el momento del roll.</li>
 *   <li>{@code MOB_WANTED}: usa {@link #mobs} (lista de ids de entidad), {@link #bounty} y
 *       {@link #maxKills} (0 = sin límite) — se elige un mob al azar de la lista y matarlo otorga
 *       la recompensa hasta que se agote el cupo.</li>
 *   <li>{@code HOUSE_COINFLIP}: usa {@link #commission} (comision de La Casa) y {@link #winChance}.</li>
 *   <li>{@code WINDFALL}: usa {@link #effects} (lista de ids de efecto de poción),
 *       {@link #effectDurationSeconds} y {@link #effectAmplifier} — se elige un efecto al azar y se
 *       aplica al instante a todos los jugadores conectados.</li>
 * </ul>
 */
public class MonopolyEventEntry {

    /** Identificador único del evento (se persiste en monopoly_data.json). */
    public String id;

    /** Nombre del {@link com.sheyito.economicmaster.monopoly.EventType}. */
    public String type;

    /** Interruptor individual: un evento desactivado nunca se sortea ni se puede forzar. */
    public boolean enabled = true;

    /** Peso para el sorteo ponderado. {@code <= 0} excluye al evento del sorteo. */
    public double weight = 1.0;

    /** Lista de multiplicadores de la que se elige uno al azar (tipos de multiplicador). */
    public List<Double> multipliers = List.of();

    /** Lista de ids de entidad de la que se elige el "mob buscado" (tipo MOB_WANTED). */
    public List<String> mobs = List.of();

    /** Recompensa extra por cada kill del mob buscado (tipo MOB_WANTED). */
    public double bounty = 0.0;

    /** Límite de muertes del mob buscado que pagan bounty en un evento; 0 = sin límite (tipo MOB_WANTED). */
    public int maxKills = 0;

    /** Comision de La Casa sobre cada apuesta, en tanto por uno (tipo HOUSE_COINFLIP). */
    public double commission = 0.05;

    /** Probabilidad de ganar en el cara o cruz (0..1), por defecto 50%. */
    public double winChance = 0.5;

    /**
     * Lista de mensajes de chat posibles al activarse el evento: el sorteo elige uno al azar.
     * Tokens (en cada mensaje): %multiplier%, %mob%, %bounty%, %commission%, %effect%, %duration%.
     * Si la lista está vacía se usa el mensaje por defecto del tipo de evento.
     */
    public List<String> messages = List.of();

    /** Lista de ids de efecto de poción de la que se elige uno al azar (tipo WINDFALL). */
    public List<String> effects = List.of();

    /** Duración en segundos del efecto de poción aplicado por el evento (tipo WINDFALL). */
    public int effectDurationSeconds = 60;

    /** Amplificador del efecto de poción (0 = nivel 1) aplicado por el evento (tipo WINDFALL). */
    public int effectAmplifier = 0;

    public MonopolyEventEntry() {
    }

    public MonopolyEventEntry(String id, String type, boolean enabled, double weight,
                              List<Double> multipliers, List<String> mobs, double bounty,
                              double commission, double winChance, List<String> messages) {
        this.id = id;
        this.type = type;
        this.enabled = enabled;
        this.weight = weight;
        this.multipliers = multipliers;
        this.mobs = mobs;
        this.bounty = bounty;
        this.commission = commission;
        this.winChance = winChance;
        this.messages = messages;
    }
}
