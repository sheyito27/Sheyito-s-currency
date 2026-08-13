# Sheyito's Currency v2 — "El casero del mundo"

Mod 100% server-side para NeoForge 1.21.1. Economía cerrada con moneda propia (Sheyicoins).

## Visión

Sin meta-juegos: sin tablero, sin mercado, sin imperio. **Minecraft sigue siendo Minecraft** —
minas, automatizas, peleas, construyes. La diferencia es que el mundo tiene un casero, Sheyito,
que cobra por los verbos naturales del juego. El reto no es aprender un sistema: es **gestionar
tu colchón de Sheyicoins bajo presión constante**. La presión es honesta, predecible y
planificable (regla de oro del Monopoly: la gente no abandona por pagar, abandona por la
sorpresa injusta).

Principio rector: **nada se imprime sin que algo queme**.

## Eje del apriete

| Sistema | Mecánica | Gancho técnico |
|---|---|---|
| **Peajes de movilidad** | Cada `/home`, `/back`, `/tpa`, waystone y cambio de dimensión cobra SC. | `PlayerTeleportEvent` + `PlayerChangedDimensionEvent`. Investigar qué mod provee `/home` `/back` en ATM10. |
| **Renta de chunks** | SC por chunk reclamado/día, incluida en el Día de Renta. Impago X días -> chunks liberados (aviso previo de Sheyito). | API FTB Chunks. Investigación pendiente. |
| **Deuda por muerte** | Morir con balance negativo = deuda. 1 día de juego de plazo; luego **embargo silencioso y brutal** de activos (whitelist de irreemplazables protegida), mensaje público, y lo perdido va a subasta. La deuda persiste si te desequipas. Morir con deuda acelera el plazo. | `LivingDeathEvent` (ya usado para la caza). |
| **Conveniencias y cuotas** | Vuelo (suscripción que expira y te baja al suelo), buffs corporativos, `/health`/`/feed` con precio escalante por uso, cuota de mantenimiento progresiva por patrimonio. | `Player.setAbilities()` + scheduler. |
| **Día de Renta** | Cada **7 días de juego**, countdown visible desde 3 días antes. Cobra chunks + cuota de mantenimiento + suscripciones + deudas vencidas, con extracto previo. **Pagar antes del día 5 otorga 10% de descuento** (recompensa la planificación). Balance negativo -> entra la deuda/embargo. | Patrón del `SalaryManager.tick`. |
| **IVA de transmisión** | Comisión (p. ej. 2%) sobre `/pay` y las transferencias de dinero del `/trade`. Cierra el agujero: las transferencias P2P también queman algo. | `EconomyManager.pay()`. |

## Faucets controlados (lo que imprime, con límite)

- **Salario rebalanceado**: `baseSalary` 10, techo `maxSalary` **100**/día, `xpPerCoin` **0.0001**,
  curva Fibonacci intacta (niveles 0-20). El progreso de nivel se vuelve brutalmente lento a
  propósito. *Nota: cambiar los defaults rompe `SalaryManagerTest` y el README — se ajustan en código.*
- **Avance de salario** `/salary early`: cobrar hoy
  `salaryForLevel(nivel) × (ticks transcurridos del día / 24000)` redondeado; pierdes el resto
  del día. Paga con `giveEarned` (da XP), fija `lastPayoutDay` (anula el pago automático del
  día), 1 vez por intervalo, error si el día recién empieza. Es el pánico/impaciencia puro:
  liquidez YA a cambio del salario completo.

## Válvulas (donde se quema el excedente, con gusto)

- **Lotería rollover**: boleto 50 SC. Pozo sembrado = jugadores activos x 50. Si nadie gana,
  cada día de juego el pozo crece (+jugadores x 50) y se anuncia. Sorteo diario; 15% se quema.
- **Casino coinflip**: `/coinflip <monto>` 50/50, edge de casa 2%.
- **Gacha**: menú vainilla (tipo `/trade`). Drops normales renovables + **exclusivos no
  renovables** numerados (tope configurable, p. ej. 50 c/u) registrados en
  `gacha_collection.json`. Drops vía `LootDataManager` (lee tablas existentes, no las modifica).
- **Sobre misterioso**: ítem aleatorio por precio fijo (gacha rápido). **Pool curado** por
  config para excluir ítems irreemplazables o de valor extremo (p. ej. la ATM Star) — el mod
  nunca entrega lo que la config excluya.
- **Ofertas flash**: descuentos temporales en sinks (gacha 2x, XP, vuelo) con countdown.
- **Compra de XP**: `/xp buy <niveles>` a tasa fija — ancla funcional del valor.
- **Subastas de embargo y remates**: bienes embargados + exclusivos. Con 2+ jugadores online,
  subasta entre jugadores; en single player, Sheyito liquida los ítems (se queman, deuda
  saldada). Cero impresión.
- **Apuestas P2P**: `/bet <jugador> <monto>`. Patrón invite/accept/deny + expiración (como
  `/trade`); gate 2+ jugadores. **Sin escrow**: el coinflip se resuelve y ahí se toma del
  perdedor y paga al ganador menos **IVA 3%**. Desconexión antes de resolver = bet cancelada.
- **Auras cosméticas**: suscripción con expiración (tipo vuelo). Partículas vanilla vía
  `ClientboundLevelParticlesPacket` (flame, soul_fire_flame, end_rod, cloud, happy_villager,
  electric_spark, snowflake). `CosmeticManager` + scheduler cada 4-5 ticks, 1-2 partículas por
  aura a jugadores en 64 bloques. Sin librería externa.
- **Cosméticos server-side**: color de nombre sobre la cabeza y tablist vía `Team`/`displayName`
  de scoreboard, títulos en `/baltop`, lore en ítems. Todo renderizado por el cliente sin
  instalar nada.
- **Hitos económicos** celebrados por Sheyito.

## Sabor narrativo (sin desbalancear)

- **Cartas Chance**: SOLO como sabor raro — eventos de bajo impacto (el pozo crece 10%, un
  mecenas te paga poco) con texto de Sheyito. Nunca desbalancean el colchón.

## Personalidad (el hilo conductor)

`SheyitoNarrator`: pool de líneas por contexto, enganchado a los eventos reales — primer chunk
("bienvenido, arrendatario"), primera muerte, expiración del vuelo, día de renta, embargo,
hitos, gacha épico, ofertas flash. Cada cobro es una conversación con un banquero que te aprieta
con estilo. Sin GUI, sin lógica de negocio mezclada: solo un suscriptor que habla.

## El reto: gestión del colchón

El desafío diario del jugador: pagar lo predecible (renta, cuotas, suscripciones), evitar lo
evitable (peajes, muerte), y resistir lo tentador (vicios). Sin azar externo: la presión es
honesta, planificable y tuya.

## Modelo de datos

- **Config** (autogenerada, recargable con `/eco reload`): `tolls.json`, `chunks.json`,
  `emergencies.json`, `subscriptions.json` (existente), `rent.json`, `lottery.json`,
  `casino.json`, `gacha.json`, `prestige.json`, `events.json`, `bets.json`, `cosmetics.json`.
- **Mundo** (`<mundo>/sheyitoscurrency/`): `gacha_collection.json` (exclusivos emitidos),
  `loans.json` (deudas/embargos pendientes), `lottery_state.json`, `auction_state.json`,
  `cosmetics_state.json` (auras activas + expiración).

## Fases de implementación

1. **Fase 1 — Sinks core SP**: narrativa base + lotería rollover + gacha + casino + compra XP +
   `/health` `/feed` + avance de salario y rebalanceo del salario.
2. **Fase 2 — El apriete**: día de renta (7 días) + deuda/embargo silencioso + subastas +
   remates.
3. **Fase 3 — Movilidad**: peajes de teletransporte/dimensión (tras investigación).
4. **Fase 4 — Territorio**: renta de chunks (tras investigación FTB Chunks).
5. **Fase 5 — Conveniencias**: vuelo, buffs, cuota de mantenimiento, ofertas flash.
6. **Fase 6 — Estatus + pulido**: cosméticos (nombre + auras), hitos, apuestas, sonidos,
   balanceo, tests, README.

## Investigación pendiente (no bloquea Fases 1-2)

- Fuente de `/home`/`/back` en ATM10 y fiabilidad de `PlayerTeleportEvent`.
- API de FTB Chunks para renta por chunks.
- API de Waystones.

## Decisiones registradas

- Sin autorregulación compleja (descartada por coste). Config estática por sistema, autogenerada,
  recargable con `/eco reload`. Admin con `/eco give|set|take` como grúa de emergencia.
- Ancla de valor dual: XP (funcional) + exclusivos del gacha (emocional).
- Salario: base 10, techo 100/día, xpPerCoin 0.0001, Fibonacci intacta.
- Avance de salario proporcional al progreso del día (ticks / 24000); pierdes el resto.
- Apuestas P2P con IVA 3%, sin escrow (cobro al resolver).
- Auras cosméticas por suscripción con expiración, partículas vanilla server-side.
- Día de Renta cada 7 días de juego, countdown desde 3 días antes, con 10% de descuento por
  pago anticipado (antes del día 5).
- Embargo silencioso y brutal, mensaje público posterior y subasta de lo perdido.
- IVA de transmisión (p. ej. 2%) sobre `/pay` y el dinero del `/trade`.
- Sobre misterioso con pool curado por config (se excluyen ítems irreemplazables como la ATM Star).
- Renta de chunks impaga -> chunks liberados con aviso.
- Subasta en SP: automática según jugadores online (2+ subasta; 1 liquida Sheyito).
- Cartas Chance solo como sabor raro.
- Bossbar de saldo, línea de colchón, reputación financiera, intereses compuestos, casa de
  empeño, tablero Monopoly, bolsa de valores, imperio tycoon, peaje por dormir, seguro de
  inventario, retención de salario por deuda, "días de colchón" en `/bal`, rueda de la fortuna,
  periódico de Sheyito, saludo de login, contrato duro y reventa de exclusivos: descartados por
  feedback de diseño.
