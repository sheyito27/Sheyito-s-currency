# Penalización por muerte

**Estado:** implementado (activado por defecto).
**Código relacionado:** `PlayerDeathPenaltyListener.java`, `DebtConfig.java`, `EconomyManager.take`.
**Patrones:** [manager](patronManager.md), [config](patronConfig.md).

## Qué es esto

Morir siempre tiene un coste económico: pierdes `penaltyPercent` (50% por defecto) de tu saldo
actual. Como el porcentaje se aplica sobre lo que tienes en ese momento, nunca puede superar tu
propio saldo — no hay forma de que morir te deje en negativo ni rompa la banca.

## Cómo funciona

`PlayerDeathPenaltyListener.onLivingDeath` escucha `LivingDeathEvent`, con las mismas dos capas de
guardas de lado-cliente/config-nula que [`MobKillListener`](cazaDeMobs.md), pero actuando
justo en el caso opuesto: solo si la víctima **es** un `ServerPlayer`.

La lógica vive en `PlayerDeathPenaltyListener.applyDeathPenalty` (extraída del listener a
propósito para poder testearla sin instanciar un `LivingDeathEvent` real):

```java
double balance = economy.getBalance(uuid);
double penalty = balance * config.penaltyPercent;
economy.take(uuid, penalty); // siempre con fondos suficientes, nunca deja saldo negativo
```

Se usa `take()` — el mismo método que usan `/pay`, `/trade` o las tiendas — porque `penalty` es
por construcción menor o igual al saldo disponible; nunca falla por fondos insuficientes.

## Comandos

No añade comandos propios; el único ajuste posible es `penaltyPercent` en
`config/sheyitoscurrency/debt.json`.

## La deuda trackeada se eliminó del todo

Esta feature nació con un diseño de dos ramas (penalización fija + deuda con plazo si el saldo
era bajo). Se simplificó a una sola rama porcentual porque generar deuda automática por morir
resultó una mecánica demasiado punitiva para el ritmo del servidor.

En ese momento se dejó `EconomyManager.charge(uuid, amount)` (resta sin comprobar fondos) y todo
`DebtManager`/`/debt` sin usar, "por si una futura feature los necesitaba". Esa futura feature
llegó con el [peaje de movilidad de Waystones](peajeMovilidadWaystones.md), que sí usa `charge()`
— pero al investigar cómo mostraba `/debt` un saldo negativo (con `-balance` en vivo, sin importar
si alguna vez se llamó `incurDebt`), quedó claro que dejar `DebtManager` vivo solo iba a mostrar un
"sin plazo registrado" que nunca cambiaría. Se decidió borrar `DebtManager`, `DebtData` y `/debt`
por completo: la deuda deja de ser un estado trackeado con plazo, es simplemente saldo negativo,
y eso ya se consulta con `/bal`.

La propuesta "Deuda con plazo estricto" en `docs/proposals.md` queda descartada tal como estaba
planteada — si algún día se retoma, sería una feature nueva desde cero, no una reactivación de
esta infraestructura.

## Cómo se conecta con otras features

Usa `take()`, igual que `/pay` o las tiendas, así que queda fuera del circuito de XP: morir nunca
debe ser una forma de subir de [nivel de salario](salarioDiario.md).
