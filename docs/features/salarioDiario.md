# Salario diario con niveles

**Estado:** implementado (rebalanceado el 2026-08-13, ver [refactorSalary.md](refactorSalary.md)).
**Código relacionado:** `SalaryManager.java`, `SalaryConfig.java`, `LevelCurve.java`, `EconomyManager.java` (XP).

## Qué es esto

Cada jugador conectado cobra automáticamente una cantidad de Sheyicoins cada cierto número de
días de juego, sin tener que pedirlo. Cuánto cobra no es fijo: depende de su **nivel**, que sube
ganando **experiencia (XP)** — y esa XP se gana simplemente jugando y generando ingresos por
otras vías (matar mobs, misiones, vender en tiendas...), no con una acción dedicada.

## Cómo funciona

**Cuándo se paga.** El servidor revisa cada ~30 segundos reales (ver `EconomicMasterScheduler`)
si a algún jugador conectado le toca cobrar (`SalaryManager.tick`, `SalaryManager.java:78-106`).
"Le toca" se mide en **días de juego**, no en tiempo real: 1 día de juego son 24.000 ticks del
mundo, así que si el servidor está apagado el reloj de juego no avanza y no hay pagos
"acumulados" esperando cuando alguien se conecta — es imposible desconectarse un mes y volver con
30 salarios atrasados. Esto se calcula con `GameTime`, comparando el día actual con el último día
en que ese jugador cobró (`SalaryManager.java:83-94`).

**Cuánto se paga.** El importe no es fijo — depende del **nivel** del jugador, interpolado
linealmente entre un salario base (nivel 0) y un salario máximo (nivel más alto posible):
cuanto más cerca del nivel máximo, más se acerca al salario máximo (`LevelCurve.salaryForLevel`,
ver [refactorSalary.md](refactorSalary.md) para los números exactos actuales).

**Cómo se sube de nivel.** Cada moneda que un jugador **gana** (no que recibe de otro jugador)
otorga una cantidad de XP proporcional (`xpPerCoin` en la config). "Ganar" incluye: matar mobs
con recompensa, completar misiones de FTB Quests, cobrar el propio salario, y vender en una
tienda o cobrar una suscripción como vendedor — todo eso pasa por `EconomyManager.giveEarned()`
(`EconomyManager.java:119-124`), que además de sumar el dinero suma la XP. En cambio, `/pay`
(transferencias directas) y los ajustes de admin usan `give()` a secas, sin XP — para que nadie
suba de nivel simplemente pasándose dinero de una cuenta a otra sin generar riqueza real.

La XP necesaria para subir de un nivel al siguiente crece siguiendo la sucesión de Fibonacci
(`LevelCurve.fibonacci`, ya lo viste): los primeros niveles se suben rápido, los últimos son
deliberadamente muy lentos de alcanzar.

## Cómo se conecta con otras features

- El nivel y la XP se guardan dentro de `EconomyManager` (mismo archivo `balances.json` que el
  saldo, ver [saldoYRanking.md](saldoYRanking.md)) — no es un sistema aparte con su propio
  archivo de datos para eso.
- `SalaryManager` sí tiene su propio archivo (`salary_data.json`) pero solo guarda **una cosa**:
  el último día en que cada jugador cobró, para no pagarle dos veces el mismo día.
- Casi todas las demás features "ganan dinero" para el jugador (mobs, misiones, tiendas,
  suscripciones) alimentan indirectamente este sistema de niveles, porque todas usan
  `giveEarned()`.
