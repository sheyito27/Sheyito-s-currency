package com.sheyito.economicmaster.data;

/**
 * Formato en disco de &lt;mundo&gt;/sheyitoscurrency/monopoly_data.json: el estado del evento
 * actualmente activo y el último periodo de sorteo ya procesado. Persistirlo permite que un
 * reinicio del servidor conserve el evento en curso (y sus parámetros ya sorteados) en lugar de
 * re-sortear o reiniciar la cuenta atrás a mitad de periodo.
 *
 * <p>Las invitaciones pendientes de cara o cruz entre jugadores NO se persisten a propósito:
 * siguen la misma lógica que {@code /trade} (ver patronInvitacionPendiente.md), estado en vivo
 * que no tiene sentido recuperar tras un reinicio.
 */
public class MonopolyData {

    /** Último índice de periodo que ya disparó su sorteo; -1 = aún no se ha sorteado nada. */
    public long lastPeriodIndex = -1;

    /** Id del evento activo; {@code null} si no hay ningún evento activo. */
    public String currentEventId = null;

    /** Multiplicador ya sorteado para el evento activo; {@code null} si el tipo no lo usa. */
    public Double currentMultiplier = null;

    /** Mob ya sorteado para el evento activo; {@code null} si el tipo no lo usa. */
    public String currentMob = null;

    /** Mensaje ya sorteado (template en bruto, con tokens sin sustituir) del evento activo; {@code null} si se usa el default. */
    public String currentMessage = null;

    /** Efecto de poción ya sorteado para el evento activo; {@code null} si el tipo no lo usa. */
    public String currentEffect = null;

    /** Muertes del mob buscado que ya pagaron bounty en el evento activo (tipo MOB_WANTED). */
    public int currentMobKills = 0;

    public static MonopolyData empty() {
        return new MonopolyData();
    }
}
