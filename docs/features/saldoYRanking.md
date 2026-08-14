# Saldo, `/bal` y `/baltop`

**Estado:** implementado.
**Código relacionado:** `EconomyManager.java`, `BalCommand.java`, `BalTopCommand.java`.

## Qué es esto

Es la base de todo el mod: cada jugador tiene un saldo en Sheyicoins guardado por el servidor, y
dos comandos para consultarlo — el propio (`/bal`), el de otro jugador (`/bal player <nombre>`),
y un ranking de los jugadores más ricos del servidor (`/baltop`). Todo lo demás del mod (salario,
pagos, tiendas, suscripciones...) en el fondo no hace más que leer y modificar este mismo saldo.

## Cómo funciona

El saldo de cada jugador no vive "en el jugador" como si fuera un objeto del inventario — vive en
un mapa en memoria dentro de `EconomyManager` (`EconomyManager.java:29`), indexado por el UUID
del jugador (un identificador único que Minecraft asigna a cada cuenta, y que no cambia aunque el
jugador se cambie el nombre). Ese mapa se guarda a disco en `balances.json` y se recarga cada vez
que arranca el servidor.

`/bal` sin argumentos (`BalCommand.java:42-47`) simplemente pregunta `EconomyManager.getBalance()`
por el saldo del jugador que ejecuta el comando y lo muestra formateado (con el nombre de moneda y
los decimales configurados — ver `Money.format()`, que ya conoces). `/bal player <jugador>`
(`BalCommand.java:49-56`) hace lo mismo pero para otro jugador — cualquiera puede consultar el
saldo de cualquiera, no hace falta ser OP.

`/baltop` (`BalTopCommand.java`) pide a `EconomyManager.top(100)` la lista de los 100 saldos más
altos, ya ordenada de mayor a menor (`EconomyManager.java:160-164`), y la muestra en páginas de 10
resultados (`BalTopCommand.java:18,31-37`) para no inundar el chat si hay muchos jugadores.

Un detalle importante para que el ranking tenga sentido: cuando un jugador nunca ha estado
conectado, no tiene entrada en el mapa de saldos todavía — `getBalance()` devuelve el
`startingBalance` configurado (`EconomyManager.java:100-102`) sin necesidad de crear una entrada
falsa de antemano.

## Cómo se conecta con otras features

`EconomyManager` es el corazón compartido: `/pay`, `/trade`, las tiendas, las suscripciones, el
salario y las recompensas de misiones/mobs todos llaman a sus métodos (`give`, `take`, `pay`,
`giveEarned`) para mover dinero — nunca tocan el saldo de otra forma. `/bal level` (parte del
mismo comando `BalCommand`, líneas 71-88) además muestra el nivel y progreso de XP del jugador,
que es el sistema de [salario diario](salarioDiario.md).
