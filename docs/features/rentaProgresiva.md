# Renta progresiva sobre ganancias

**Estado:** implementado.
**Código relacionado:** `RentConfig.java`, `RentManager.java`, `RentLogic.java`, `RentData.java`,
`EconomyManager.java` (`give()`).
**Patrones:** [manager](patronManager.md), [config](patronConfig.md); comparte cadencia y archivo
de config (`rent.json`) con la [renta de force-load de chunks](rentaDeChunks.md#renta-de-force-load).

## Qué es esto

Cada `intervalGameDays` días de juego (7 por defecto), se cobra un porcentaje sobre lo que **ganó**
cada jugador en ese periodo — no su patrimonio total, no un balance neto — según en qué tramo caiga
esa ganancia:

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

**Ganancia bruta, no un balance neto — corregido tras probarlo en la práctica.** La primera versión
comparaba el saldo al principio y al final del periodo (`saldoActual - saldoAlPrincipio`), así que
una pérdida en medio del periodo "tapaba" una ganancia real. El usuario lo detectó y lo corrigió:
**si ganás 10.000 esta semana pero por separado perdés 20.000, se te cobra el 10% de los 10.000
ganados (1.000), no 0** — aunque en conjunto hayas terminado la semana con menos dinero que al
empezar. Perder saldo es gasto, algo completamente ajeno a esta renta: nunca genera un "crédito"
que compense una ganancia, ni esa ni una futura.

Para conseguir esto, la renta ya no compara saldos: **acumula directamente cada ingreso** a medida
que ocurre (`EconomyManager.give()`, ver más abajo) y grava esa suma acumulada en cada cobro,
reiniciándola a 0 después. Lo que gastes o pierdas mientras tanto nunca se resta de esa cuenta.

**Esta renta sí puede dejarte en números rojos, a propósito**: a diferencia de cualquier otro
cobro de este mod (que usan `EconomyManager.take()` y se bloquean o se saltan si no alcanza el
saldo), esta usa `EconomyManager.charge()` — la misma vía sin comprobación que usa `/eco charge`.
La idea explícita del usuario es que la renta pueda llevarte a la banca rota. Como
`EconomyManager.setBalance()` ya avisa a `EmbargoManager` en cuanto el saldo cruza de ≥0 a
negativo, esta es la primera vía de gameplay real (no solo `/eco charge` de admin) que dispara el
[plazo de gracia del embargo](embargoDeudas.md) — sin haber tenido que tocar nada de ese sistema.

## Cómo funciona

**`EconomyManager.give(uuid, amount)`** es el único punto por el que entra dinero desde fuera del
jugador — `/pay` recibido, venta en tienda, ingreso de suscripción, salario, recompensas de misión
o de caza, `/eco give` — así que es ahí donde se engancha el seguimiento, no en `RentManager`:

```java
public void give(UUID uuid, double amount) {
    setBalance(uuid, getBalance(uuid) + amount);
    if (amount > 0 && RentManager.get() != null) {
        RentManager.get().trackGain(uuid, amount);
    }
}
```

`RentManager` guarda, por jugador, `lastRentDay` (-1 si nunca se le cobró) y `accumulatedGains` (lo
acumulado desde entonces). `trackGain` solo suma — no necesita saber qué día es, así que un
registro nuevo empieza con `lastRentDay = -1`.

Cada pasada del scheduler (~30s, no hace falta más precisión que la de día), `processDueRent`
recorre los jugadores con algún registro:

- Un registro con `lastRentDay = -1` (nunca cobrado) está **inmediatamente pendiente** — no hay
  patrimonio previo del que preocuparse, porque `accumulatedGains` solo cuenta lo que entró después
  de que este sistema empezara a rastrear a ese jugador, nunca nada de antes.
- Si ya pasaron `intervalGameDays` desde `lastRentDay`, se cobra
  `RentLogic.taxFor(accumulatedGains, tramos)` vía `EconomyManager.charge()`, y tanto
  `accumulatedGains` como `lastRentDay` se reinician (a 0 y al día actual) — cobre algo o no.

Toda la aritmética de tramos vive en `RentLogic` (pura, sin tocar `EconomyManager` ni persistencia)
para poder testearla con listas de tramos cualquiera, sin necesitar un servidor.

`forceProcess` (detrás de `/sc rent force`) reutiliza el mismo método privado de cobro
(`chargeAndAdvance`) que la pasada periódica, pero sin la comprobación de "¿ya tocaba?" — cobra lo
acumulado en el momento, aunque sea la primerísima vez que se llama para ese jugador.

## Comandos

- `/sc rent force <player>` (OP nivel 2) — fuerza un cobro inmediato de lo que ese jugador tenga
  acumulado, ignorando si ya pasaron `intervalGameDays` de verdad. Cobra también la renta de
  force-load de chunks del mismo jugador en la misma llamada (`RentCommand.java`) — evita tener
  que esperar 7 días de juego reales para probar que un cobro dispara bien. No hace nada si el
  jugador nunca ganó dinero desde que existe este sistema (nada acumulado, nada que forzar).

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
