# Salario diario con niveles

**Estado:** implementado (rebalanceado el 2026-08-13, ver [refactorSalary.md](refactorSalary.md)).
**Código relacionado:** `SalaryManager.java`, `SalaryConfig.java`, `LevelCurve.java`, `EconomyManager.java` (XP).
**Patrones:** [manager con ciclo de vida](patronManager.md), [config](patronConfig.md).

## Qué es esto

Cada jugador conectado cobra Sheyicoins automáticamente cada cierto número de días de juego. El
importe depende de su **nivel**, que sube ganando **XP interna del mod** — no confundir con la
XP vanilla de Minecraft, ver [`/buy xp`](compraXP.md).

## Cómo funciona

**Cuándo se paga.** `SalaryManager.tick` (`SalaryManager.java:78-106`), llamado cada ~30s por el
scheduler general, compara el día de juego actual contra el último día en que cada jugador cobró
(`GameTime`). Medido en días de juego, no reales: servidor apagado no genera pagos atrasados.

**Cuánto se paga.** Interpolación lineal entre salario base (nivel 0) y máximo (nivel tope) según
`LevelCurve.salaryForLevel` — cifras exactas en [refactorSalary.md](refactorSalary.md).

**Cómo se sube de nivel.** Cada moneda **ganada** (no recibida por pago de otro jugador) otorga
XP proporcional (`xpPerCoin`). "Ganada" = pasa por `EconomyManager.giveEarned()`
(`EconomyManager.java:119-124`): mobs, misiones, salario mismo, venta en tienda/suscripción.
`/pay` y `/eco give` usan `give()` sin XP, para que nadie suba de nivel solo moviendo dinero entre
cuentas.

La XP requerida por nivel crece en Fibonacci (`LevelCurve.fibonacci`): primeros niveles rápidos,
últimos deliberadamente lentos.

## Cómo se conecta con otras features

- Nivel y XP viven dentro de `EconomyManager` (mismo `balances.json`, ver
  [saldoYRanking.md](saldoYRanking.md)), no en un archivo aparte.
- `SalaryManager` solo persiste el último día de cobro de cada jugador (`salary_data.json`).
- Cualquier feature que "gana dinero" (mobs, misiones, tiendas, suscripciones) alimenta este
  sistema indirectamente vía `giveEarned()`.
