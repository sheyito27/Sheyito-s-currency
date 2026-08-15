# Renta progresiva sobre ganancias

**Estado:** implementado.
**Código relacionado:** `RentConfig.java`, `RentManager.java`, `RentLogic.java`, `RentData.java`.
**Patrones:** [manager](patronManager.md), [config](patronConfig.md); comparte cadencia y archivo
de config (`rent.json`) con la [renta de force-load de chunks](rentaDeChunks.md#renta-de-force-load).

## Qué es esto

Cada `intervalGameDays` días de juego (7 por defecto), se mira cuánto **ganó** cada jugador en ese
periodo — no su patrimonio total — y se le cobra un porcentaje según en qué tramo caiga esa
ganancia:

| Ganancia en el periodo | Tipo |
|---|---|
| 1 - 9.999 | 10% |
| 10.000 - 99.999 | 20% |
| 100.000 - 999.999 | 30% |
| 1.000.000 en adelante | 40% (tope) |

**Tipo plano del tramo, no marginal**: toda la ganancia se grava al porcentaje de su tramo final —
una ganancia de 150.000 (tramo de 100.000, 30%) paga `150.000 × 30% = 45.000`, no la suma de cada
franja a su propio tipo (que daría 34.000). Confirmado explícitamente con el usuario frente a la
alternativa de cálculo marginal (como el IRPF real): más simple, y coherente con que ningún otro
cobro de este mod usa cálculo marginal (ni el `n^1.5` de chunks, ni el doble corte del IVA).

**Solo ganancias, nunca patrimonio**: si tu saldo bajó en el periodo (gastaste más de lo que
ganaste), no se cobra nada — y el punto de partida del siguiente periodo se reajusta hacia abajo,
así que una recuperación posterior sí cuenta como ganancia nueva contra ese punto más bajo. Nunca
se cobra sobre el saldo total que ya tenías acumulado de antes.

**Las pérdidas nunca se compensan**, a propósito (confirmado con el usuario): perder saldo es un
gasto, algo completamente ajeno a esta renta — no genera ningún "crédito" que reste de una ganancia
futura. Si perdés 5.000 una semana y ganás 5.000 la siguiente, la segunda semana igual se grava como
ganancia nueva, aunque en neto solo hayas vuelto al punto de partida.

**Esta renta sí puede dejarte en números rojos, a propósito**: a diferencia de cualquier otro
cobro de este mod (que usan `EconomyManager.take()` y se bloquean o se saltan si no alcanza el
saldo), esta usa `EconomyManager.charge()` — la misma vía sin comprobación que usa `/eco charge`.
La idea explícita del usuario es que la renta pueda llevarte a la banca rota. Como
`EconomyManager.setBalance()` ya avisa a `EmbargoManager` en cuanto el saldo cruza de ≥0 a
negativo, esta es la primera vía de gameplay real (no solo `/eco charge` de admin) que dispara el
[plazo de gracia del embargo](embargoDeudas.md) — sin haber tenido que tocar nada de ese sistema.

## Cómo funciona

`RentManager` guarda, por jugador, `lastRentDay` (último día de juego en que se comprobó) y
`balanceSnapshot` (el saldo en ese momento). Cada pasada del scheduler (~30s, no hace falta más
precisión que la de día):

1. Itera **todos** los jugadores conocidos vía `EconomyManager.top(Integer.MAX_VALUE)` — se
   reutiliza ese método existente (ya devuelve todos los saldos si el límite es suficientemente
   grande) en vez de añadir un getter nuevo a `EconomyManager`.
2. Si un jugador no tiene registro todavía, se le crea uno (snapshot = saldo actual, día actual)
   **sin cobrar nada** — nunca se cobra retroactivamente por un periodo anterior a que existiera
   el registro.
3. Si ya pasaron `intervalGameDays` desde `lastRentDay`: `ganancia = max(0, saldoActual -
   snapshot)`; si es mayor que 0, se cobra `RentLogic.taxFor(ganancia, tramos)` vía
   `EconomyManager.charge()` — sin comprobar fondos, puede dejar el saldo negativo a propósito
   (ver más abajo). El snapshot se actualiza al saldo actual (ya descontado el cobro, negativo o
   no) y `lastRentDay` al día actual, cobre o no haya habido ganancia.

Toda la aritmética de tramos vive en `RentLogic` (pura, sin tocar `EconomyManager` ni persistencia)
para poder testearla con listas de tramos cualquiera, sin necesitar un servidor.

Los pasos 2-3 (sembrar la base la primera vez, o cobrar y avanzar el snapshot) están extraídos en
métodos privados (`seedBaseline`/`chargeAndAdvance`) que también reutiliza `forceProcess` — el
método detrás de `/sc rent forzar` — así que forzar un cobro de prueba ejecuta exactamente la misma
lógica que la pasada periódica real, solo que sin esperar a que `intervalGameDays` haya pasado de
verdad.

## Comandos

- `/sc rent forzar <jugador>` (OP nivel 2) — fuerza una pasada de cobro inmediata para ese
  jugador, ignorando si ya pasaron `intervalGameDays` de verdad. Cobra también la renta de
  force-load de chunks del mismo jugador en la misma llamada (`RentCommand.java`) — evita tener
  que esperar 7 días de juego reales para probar que un cobro dispara bien.

El único ajuste de config es `config/sheyitoscurrency/rent.json` (`enabled`, `intervalGameDays`,
`profitBrackets` — lista de `{minProfit, percent}` — y `forceLoadRentBase`, que pertenece a la
[renta de force-load](rentaDeChunks.md#renta-de-force-load), no a esta).

## Cómo se conecta con otras features

Es, a propósito, la única feature de todo el mod que usa `charge()` en vez de `take()` fuera de
`/eco charge` — así que es el primer disparador real (no de admin) del
[embargo por deuda](embargoDeudas.md). La renta de force-load de chunks, en cambio, sigue usando
`take()` + auto-unload todo-o-nada (nunca deja saldo negativo) — ambas rentas comparten cadencia
pero no filosofía de cobro. Es independiente del IVA de transmisión (que solo se aplica a
transacciones entre jugadores, no a esta renta periódica).
