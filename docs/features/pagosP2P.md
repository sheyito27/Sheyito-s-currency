# `/pay` — transferencias directas entre jugadores

**Estado:** implementado.
**Código relacionado:** `PayCommand.java`, `EconomyManager.pay()`.
**Patrones:** [comandos](patronComandos.md), [validar luego mutar](patronValidarLuegoMutar.md).

## Qué es esto

`/pay <jugador> <cantidad>` envía dinero de tu saldo al de otro jugador al instante, sin
confirmaciones ni GUI.

## Cómo funciona

`EconomyManager.pay(from, to, amount)` (`EconomyManager.java:152-158`) sigue
[validar-luego-mutar](patronValidarLuegoMutar.md): `take()` primero, `give()` solo si eso tuvo
éxito.

`PayCommand.java` valida antes de llamar: no puedes pagarte a ti mismo (líneas 39-42); si `pay()`
falla por saldo insuficiente, mensaje + `TransactionSounds.failure` (línea 46). Si funciona,
ambos jugadores reciben confirmación (líneas 51, 56) y el receptor se registra por nombre
(`trackName`, línea 50) para aparecer en `/baltop` aunque nunca hubiera tenido saldo.

Decisión de diseño: usa `give()`, no `giveEarned()` — el receptor no gana XP de nivel (ver
[salario diario](salarioDiario.md)); si diera XP, dos jugadores podrían subir de nivel
indefinidamente pasándose la misma moneda.

## Cómo se conecta con otras features

Ejemplo más simple de que todo el mod comparte `EconomyManager` como única fuente de verdad del
dinero (ver [saldoYRanking.md](saldoYRanking.md)).
