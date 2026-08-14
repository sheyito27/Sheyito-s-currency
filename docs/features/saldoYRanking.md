# Saldo, `/bal` y `/baltop`

**Estado:** implementado.
**Código relacionado:** `EconomyManager.java`, `BalCommand.java`, `BalTopCommand.java`.
**Patrones:** [comandos](patronComandos.md).

## Qué es esto

Base del mod: cada jugador tiene un saldo en Sheyicoins guardado por el servidor, con dos
comandos de consulta (`/bal`, `/bal player <jugador>`) y un ranking (`/baltop`). El resto de
features (salario, pagos, tiendas, suscripciones) solo leen y modifican este mismo saldo.

## Cómo funciona

El saldo vive en un mapa en memoria dentro de `EconomyManager` (`EconomyManager.java:29`),
indexado por UUID (identificador estable del jugador, no cambia si se renombra). Se persiste en
`balances.json`.

`/bal` (`BalCommand.java:42-47`) pregunta `getBalance()` por el saldo propio; `/bal player
<jugador>` (`BalCommand.java:49-56`) igual pero para cualquier otro, sin requerir OP.

`/baltop` (`BalTopCommand.java`) pide a `EconomyManager.top(100)` los 100 saldos más altos ya
ordenados (`EconomyManager.java:160-164`), paginados de 10 en 10 (`BalTopCommand.java:18,31-37`).

Un jugador sin entrada en el mapa (nunca recibió dinero) no está "ausente" de forma especial:
`getBalance()` simplemente devuelve `startingBalance` (config) sin crear una entrada
(`EconomyManager.java:100-102`).

## Cómo se conecta con otras features

`EconomyManager` es la única fuente de verdad del dinero — todas las demás features llaman a
`give`/`take`/`pay`/`giveEarned`, nunca tocan el saldo de otra forma. `/bal level` (mismo comando,
`BalCommand.java:71-88`) muestra el nivel/XP del [salario diario](salarioDiario.md).
