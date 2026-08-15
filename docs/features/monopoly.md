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
el evento. El mensaje de chat soporta los tokens `%multiplier%`, `%mob%`, `%bounty%`, `%commission%`,
`%effect%` y `%duration%`.

**Mensajes variables.** Cada evento puede declarar una lista `messages` con varias variantes de
chat; en el momento del sorteo se elige **una al azar** y queda fijada para todo el evento (se
persiste en `monopoly_data.json` igual que el multiplicador o el mob, así un reinicio no cambia el
anuncio). Si la lista está vacía, se usa el mensaje por defecto del tipo. Se guarda el template en
bruto: los tokens se sustituyen al anunciar, no al sortear. Si un servidor ya tenía un
`monopoly.json` generado con el antiguo campo `message` (String único), ese campo se ignora y habrá
que regenerar o editar el archivo para usar `messages`.

**Cómo "se entera" el resto del mod.** Nadie llama al sorteo: los consumidores consultan getters que
devuelven valores neutros si no hay evento activo del tipo correspondiente:

- `SalaryManager.tick` multiplica el salario por `MonopolyManager.salaryMultiplier()` (1.0 si no
  aplica) y lo redondea con `Money.round`.
- `FtbQuestsIntegration` multiplica la recompensa de misión por `questRewardMultiplier()`.
- `MonopolyEventListener` (registrado en el bus de NeoForge) escucha `LivingDamageEvent.Pre` para
  registrar quién daña al mob buscado, y `LivingDeathEvent` para repartir el `bounty` extra por igual
  entre esos contribuidores (`giveEarned`, cuenta como XP ganada). Es independiente de la caza de
  `mobs.json`: el bounty se paga aunque la caza esté apagada y se suma a cualquier recompensa normal.
  Una vez agotado el cupo de muertes (ver más abajo), deja de registrar daño y de pagar.

## Tipos de evento

| `type` | Campos que usa | Efecto |
|---|---|---|
| `SALARY_MULTIPLIER` | `multipliers` | El salario diario se multiplica por un valor de la lista. |
| `QUEST_REWARD_MULTIPLIER` | `multipliers` | Las recompensas de misiones se multiplican por un valor de la lista. |
| `MOB_WANTED` | `mobs`, `bounty`, `maxKills` | Se elige un mob de la lista; al morir, el `bounty` se reparte por igual entre todos los que lo dañaron, hasta un máximo de `maxKills` muertes pagadas por evento (0 = sin límite). Con `maxKills` a 1 sirve para un "boss buscado" (un evento que paga una sola muerte de un boss). |
| `HOUSE_COINFLIP` | `commission`, `winChance` | Habilita `/monopoly coinflip`. |
| `WINDFALL` | `effects`, `effectDurationSeconds`, `effectAmplifier` | Efecto instantáneo: se elige un efecto de poción de la lista y se aplica una sola vez a todos los jugadores conectados. |

Un evento es "válido" para el sorteo solo si su `type` existe y tiene los campos que necesita
(p. ej. una entrada `SALARY_MULTIPLIER` sin `multipliers` nunca se sortea y se avisa por log).

## Evento WINDFALL (efecto instantáneo)

Es la categoría de eventos "de un disparo": cuando el sorteo elige un `WINDFALL`, el mod hace dos
cosas en el mismo instante:

1. Anuncia el evento por el chat (el mensaje puede usar `%effect%` — nombre legible del efecto — y
   `%duration%` — duración en segundos).
2. Aplica al momento el efecto de poción elegido a **todos los jugadores conectados**, con la
   duración (`effectDurationSeconds`) y el amplificador (`effectAmplifier`, 0 = nivel 1) que
   configure el evento.

Detalles de diseño:

- El efecto se elige **al azar de `effects` en el momento del roll** y se persiste en
  `monopoly_data.json` (campo `currentEffect`), igual que el multiplicador, el mob o el mensaje.
- Es un **disparo único**: quien no esté conectado en ese momento no lo recibe (igual que una lluvia
  de dinero). El evento sigue "activo" hasta el siguiente sorteo, pero ya no vuelve a aplicar nada.
- El id de cada efecto es un id de registro vanilla, p. ej. `minecraft:regeneration`,
  `minecraft:speed` o `minecraft:absorption`. Si el id no existe, se avisa por log y no se aplica nada.
- `applyWindfall` se ejecuta dentro de `roll` (MonopolyManager.java), justo después del broadcast.

## Reparto del bounty del mob buscado

Durante un evento `MOB_WANTED`, el bounty **no** lo cobra solo quien da la última estocada: se
reparte por igual entre todos los jugadores que dañaron al mob.

- Cada golpe válido se registra en `LivingDamageEvent.Pre` (el daño ya pasó armadura y reducciones,
  así que un golpe bloqueado a 0 no cuenta). Da igual si es melé o a distancia: el atacante se obtiene
  de `DamageSource.getEntity()`, que para proyectiles devuelve al jugador que disparó.
- El registro es **por instancia de mob** (UUID de la entidad) y recuerda también el nombre de cada
  contribuidor, así el cobro y el mensaje llegan aunque el jugador se haya ido antes de que el mob
  muera.
- Al morir el mob, cada contribuidor recibe `Money.round(bounty / nº de contribuidores)`. El resto de
  céntimos que no divida exacto **no se acuña** (se quema), igual que el redondeo del resto del mod:
  el dinero nunca se crea de más. Ejemplo: bounty 100 con 3 jugadores → 33.33 cada uno (99.99; el
  0.01 restante no se crea).
- La memoria se autolimpia: un mob que lleva 60s sin recibir daño se olvida (mobs que se despawnean o
  vagan sin morir), y al terminar el evento se limpia el registro completo.
- Consecuencia: si un mob muere por el entorno (lava, caída) tras haber recibido daño de jugadores,
  sus contribuidores **sí** cobran el reparto; si nadie lo dañó, nadie cobra.

**Cupo de muertes (`maxKills`).** Cada evento `MOB_WANTED` puede limitar cuántas muertes del mob
pagadas admite: el campo `maxKills` (0 = sin límite, comportamiento original). Solo cuentan las
muertes que de verdad pagaron (un mob que muere por el entorno sin contribuidores no gasta cupo).

- El contador se lleva en `MonopolyManager.mobWantedKills`, se **persiste** en `monopoly_data.json`
  (un reinicio no reabre el cupo ni re-sortea) y se reinicia con cada sorteo nuevo.
- Al alcanzar el tope, `mobBountyExhausted()` pasa a `true` y se anuncia en el chat
  ("La recompensa por X se ha agotado"). El evento **no termina**: sigue activo hasta el siguiente
  sorteo, pero ya no paga ni registra daño.
- `/monopoly status` muestra el cupo consumido: `mob buscado: minecraft:zombie (25.00 Sheyicoins,
  3/5 muertes)` y añade "recompensa agotada" cuando toca. Sin `maxKills` no se muestra ningún contador.

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
