# Eventos económicos "Monopoly"

**Estado:** implementado (2026-08-14).
**Código relacionado:** `MonopolyManager.java`, `MonopolyEventListener.java`, `MonopolyConfig.java`,
`MonopolyEventEntry.java`, `EventType.java`, `MonopolyCommand.java`, `MonopolyData.java`.
**Patrones:** [manager con ciclo de vida](patronManager.md), [config](patronConfig.md),
[invitación pendiente](patronInvitacionPendiente.md).

## Qué es esto

Cada cierto tiempo (por defecto **un evento por día de juego**, configurable) el servidor sortea un
evento de la lista de `monopoly.json` y lo anuncia en el chat. El evento elegido añade una regla
económica temporal que se mantiene activa **hasta el siguiente sorteo**: multiplicar salarios o
recompensas de misiones, marcar un "mob buscado" que da dinero extra, o activar el **cara o cruz
contra La Casa**.

El sorteo es **ponderado** (cada evento tiene un `weight`), cada evento se puede habilitar o
deshabilitar individualmente con su flag `enabled`, y todo el sistema se apaga con el `enabled`
global del JSON.

## Cómo funciona

**Cadencia.** `MonopolyManager.tick` (`MonopolyManager.java`), llamado cada ~30s por el scheduler
general, calcula el índice de periodo como `ticks del overworld / (24000 / eventsPerDay)`. Cuando el
índice avanza respecto al último periodo ya sorteado, se lanza un sorteo nuevo. Al arrancar un mundo
nuevo se sortea inmediatamente; un reinicio a mitad de periodo **no** re-sortea: el evento activo y
sus parámetros ya sorteados se persisten en `<mundo>/sheyitoscurrency/monopoly_data.json`.

**Sorteo.** `roll` filtra los eventos habilitados, con `weight > 0` y "válidos" (ver más abajo), y
elige uno ponderado. Para los tipos con listas (`multipliers`, `mobs`), además se elige al azar el
valor concreto de la lista en ese mismo momento, y ese valor queda fijado (y persistido) para todo
el evento. El mensaje de chat soporta los tokens `%multiplier%`, `%mob%`, `%bounty%` y `%commission%`.

**Cómo "se entera" el resto del mod.** Nadie llama al sorteo: los consumidores consultan getters que
devuelven valores neutros si no hay evento activo del tipo correspondiente:

- `SalaryManager.tick` multiplica el salario por `MonopolyManager.salaryMultiplier()` (1.0 si no
  aplica) y lo redondea con `Money.round`.
- `FtbQuestsIntegration` multiplica la recompensa de misión por `questRewardMultiplier()`.
- `MonopolyEventListener` (registrado en el bus de NeoForge) escucha `LivingDeathEvent`: si el evento
  activo es `MOB_WANTED` y el mob muerto coincide con el sorteado, paga `bounty` extra
  (`giveEarned`, cuenta como XP ganada). Es independiente de la caza de `mobs.json`: el bounty se
  paga aunque la caza esté apagada y se suma a cualquier recompensa normal.

## Tipos de evento

| `type` | Campos que usa | Efecto |
|---|---|---|
| `SALARY_MULTIPLIER` | `multipliers` | El salario diario se multiplica por un valor de la lista. |
| `QUEST_REWARD_MULTIPLIER` | `multipliers` | Las recompensas de misiones se multiplican por un valor de la lista. |
| `MOB_WANTED` | `mobs`, `bounty` | Se elige un mob de la lista; matarlo paga `bounty` extra por cada uno. |
| `HOUSE_COINFLIP` | `commission`, `winChance` | Habilita `/monopoly coinflip`. |

Un evento es "válido" para el sorteo solo si su `type` existe y tiene los campos que necesita
(p. ej. una entrada `SALARY_MULTIPLIER` sin `multipliers` nunca se sortea y se avisa por log).

> **WINDFALL** (lluvia de dinero a todos los jugadores conectados) está **planeado pero no
> implementado**: queda documentado como comentario en `EventType.java` y `MonopolyConfig.java`.

## Cara o cruz contra La Casa

Durante un evento `HOUSE_COINFLIP`, `MonopolyManager.coinflipVsHouse` permite apostar:

- El jugador paga `cantidad × (1 + commission)` (la comisión se **quema** del sistema, es un sink
  deliberado, igual que el IVA de transmisión propuesto).
- Con probabilidad `winChance` (0.5 por defecto) gana el doble de la apuesta. Matemáticamente, con
  comisión `c` la esperanza de pérdida por apuesta es exactamente `-c × apuesta`: La Casa siempre
  gana a la larga, pero la derrota nunca es el 100%.
- El premio se entrega con `EconomyManager.give()` (sin XP de nivel), igual que `/pay`, para que no
  se pueda "farmear nivel" apostando en bucle.

**Versión entre jugadores.** `/monopoly coinflip <cantidad> <jugador>` no mueve nada todavía: solo
registra una invitación pendiente (patrón de invitación con expiración, igual que `/trade` y
`/subscribe`). Al aceptar (`/monopoly accept`), **ambos** pagan `cantidad × (1 + commission)` y el
ganador (sorteo 50/50) recibe el doble de la apuesta; si uno de los dos se quedó sin fondos, la
apuesta se anula sin mover nada ("validar luego mutar"). Las invitaciones pendientes **no se
persisten**: son estado en vivo, igual que las sesiones de `/trade`, y expiran al cabo de 60s o con
el reinicio del servidor.

## Comandos

Públicos:

- `/monopoly status` — evento activo y cuándo llega el siguiente.
- `/monopoly coinflip <cantidad>` — cara o cruz contra La Casa (solo durante evento `HOUSE_COINFLIP`).
- `/monopoly coinflip <cantidad> <jugador>` — retar a otro jugador (solo durante el evento).
- `/monopoly accept` / `/monopoly deny` — aceptar o rechazar un reto pendiente.

Administración (OP 2):

- `/monopoly roll [id]` — fuerza un sorteo ahora (con `id`, fuerza ese evento concreto).
- `/monopoly end` — termina el evento actual; el siguiente llega en la próxima frontera de periodo.

## Cómo se conecta con otras features

- Configuración en `config/sheyitoscurrency/monopoly.json` (se autogenera, igual que el resto).
- El estado activo viaja en el mundo (`monopoly_data.json`), como todos los datos de jugadores.
- No duplica ni sustituye la caza de mobs ni las recompensas de misiones: se suma encima.
- Sigue el patrón de manager singleton + autoguardado perezoso (`saveIfDirty` cada ~30s).
