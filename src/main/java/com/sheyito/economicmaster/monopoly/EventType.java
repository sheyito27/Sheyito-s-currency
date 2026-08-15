package com.sheyito.economicmaster.monopoly;

/**
 * Tipos de evento económico que el sorteo de Monopoly puede elegir. Cada tipo lee solo los
 * campos correspondientes de su {@code MonopolyEventEntry}:
 * <ul>
 *   <li>{@link #SALARY_MULTIPLIER}: multiplica el salario diario por un valor de su lista.</li>
 *   <li>{@link #QUEST_REWARD_MULTIPLIER}: multiplica las recompensas de misiones (FTB Quests).</li>
 *   <li>{@link #MOB_WANTED}: elige un mob de una lista; matarlo otorga una recompensa extra.</li>
 *   <li>{@link #HOUSE_COINFLIP}: activa el cara o cruz contra La Casa o entre jugadores.</li>
 *   <li>{@link #WINDFALL}: efecto instantáneo: aplica al momento un efecto de poción (elegido de
 *       una lista) a todos los jugadores conectados.</li>
 * </ul>
 */
public enum EventType {

    SALARY_MULTIPLIER,
    QUEST_REWARD_MULTIPLIER,
    MOB_WANTED,
    HOUSE_COINFLIP,
    WINDFALL;

    /**
     * @return el tipo cuyo nombre coincide con {@code id}, o {@code null} si el string no es
     * un tipo válido (entradas mal escritas en monopoly.json se ignoran en el sorteo).
     */
    public static EventType fromId(String id) {
        if (id == null) {
            return null;
        }
        try {
            return valueOf(id.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
