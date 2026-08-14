# Deuda por muerte

**Estado:** implementado (activado por defecto).
**Código relacionado:** `PlayerDeathDebtListener.java`, `DebtManager.java`, `DebtConfig.java`,
`DebtCommand.java`, `EcoCommand.java` (`/eco charge`), `EconomyManager.charge`.
**Patrones:** [manager](patronManager.md), [config](patronConfig.md), [comandos](patronComandos.md).

## Qué es esto

Morir tiene un coste económico, con dos ramas según cuánto tengas en ese momento
(`balanceThreshold`, 500 SC por defecto):

- **Saldo ≤ umbral:** se te cobra una penalización fija (`penaltyAmount`, 500 SC por defecto).
  Como no se limita a tus fondos disponibles, puede dejarte en negativo — eso es la **deuda**,
  con un plazo estricto (`deadlineGameDays`, 1 día por defecto) para volver a saldo ≥ 0.
- **Saldo > umbral:** se te cobra un porcentaje de tu patrimonio actual (`penaltyPercent`, 30%
  por defecto). Un porcentaje de un saldo positivo nunca supera el propio saldo, así que esta
  rama nunca genera deuda.

Es deliberado: a quien ya anda mal de dinero, morir lo empuja a una espiral de deuda; a quien
tiene colchón, le cuesta una tajada proporcional en vez de una cantidad fija.

## Cómo funciona

`PlayerDeathDebtListener.onLivingDeath` escucha `LivingDeathEvent`, con las mismas dos capas de
guardas de lado-cliente/config-nula que [`MobKillListener`](cazaDeMobs.md), pero actuando
justo en el caso opuesto: solo si la víctima **es** un `ServerPlayer`.

La lógica de las dos ramas vive en `PlayerDeathDebtListener.applyDeathPenalty` (extraído del
listener a propósito para poder testearlo sin instanciar un `LivingDeathEvent` real):

```java
double balance = economy.getBalance(uuid);
if (balance <= config.balanceThreshold) {
    economy.charge(uuid, config.penaltyAmount);       // puede dejarlo negativo
    if (economy.getBalance(uuid) < 0) {
        debtManager.incurDebt(uuid, GameTime.currentDay(server) + config.deadlineGameDays);
    }
} else {
    economy.take(uuid, balance * config.penaltyPercent); // siempre con fondos suficientes
}
```

### El sobregiro controlado en `EconomyManager`

Antes de esta feature, `EconomyManager.setBalance` limitaba el resultado a `Math.max(0.0,
amount)` — el saldo nunca podía ser negativo, y ninguna otra feature lo necesitaba. Se quitó ese
límite y se añadió `EconomyManager.charge(uuid, amount)`: una resta que, a diferencia de `take()`,
no exige fondos suficientes y puede dejar el saldo en negativo.

Es seguro porque nada más dependía de ese límite: `take()` (usado por `/pay`, `/trade`, tiendas)
ya validaba fondos *antes* de mutar; `give()`/`giveEarned()` solo suman, y sumar ingresos a un
saldo negativo simplemente lo acerca a 0 en vez de perdonarlo de golpe — es el mecanismo natural
de "pagar la deuda con ingresos futuros" (salario, `/pay` recibido); y `/eco set` ya restringía
su argumento de Brigadier a valores ≥ 0. `charge()` es hoy el único punto que produce sobregiro,
usado por este listener y por `/eco charge <jugador> <cantidad>` (herramienta de admin para
forzar/probar saldo negativo sin esperar a que alguien muera).

### `DebtManager`

Sigue el [patrón de manager con ciclo de vida](patronManager.md). Solo persiste el plazo
(`Map<UUID, Long> dueGameDay` en `debt_data.json`) — el importe adeudado nunca se duplica, se lee
en vivo como `-EconomyManager.getBalance(uuid)` cuando es negativo. `isOverdue(server, uuid)`
comprueba si el plazo ya venció y el saldo sigue en negativo; una deuda se limpia sola en cuanto
el saldo vuelve a ≥ 0 (pago parcial o total vía ingresos normales).

## Comandos

- `/debt` — tu deuda actual (importe, plazo, si está vencida).
- `/debt player <jugador>` — la deuda de otro jugador.
- `/eco charge <jugador> <cantidad>` (OP) — resta sin comprobar fondos, puede dejar negativo.

## Fuera de alcance (a propósito)

`DebtManager.isOverdue` es el único gancho expuesto hacia el futuro: qué pasa cuando una deuda
vence sin pagarse (embargo, subasta de bienes...) es la feature pendiente "Embargo silencioso y
brutal" en `docs/proposals.md` — esta entrega solo genera y trackea la deuda, no castiga el
impago.

## Cómo se conecta con otras features

Cobrar por debajo del umbral usa `charge()` (fuera del circuito de XP); cobrar por encima usa
`take()`, igual que `/pay` o las tiendas. Ninguna de las dos ramas otorga XP — morir nunca debe
ser una forma de subir de [nivel de salario](salarioDiario.md).
