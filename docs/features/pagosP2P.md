# `/pay` — transferencias directas entre jugadores

**Estado:** implementado.
**Código relacionado:** `PayCommand.java`, `EconomyManager.pay()`.

## Qué es esto

El comando más simple del mod: `/pay <jugador> <cantidad>` envía dinero de tu saldo al de otro
jugador, al instante, sin confirmaciones ni GUI — como una transferencia bancaria directa.

## Cómo funciona

Todo pasa por un único método, `EconomyManager.pay(from, to, amount)` (`EconomyManager.java:152-158`),
que hace dos cosas en orden estricto: primero intenta **quitarle** el dinero al que paga
(`take()`, que falla y no hace nada si no tiene saldo suficiente), y solo si eso funciona, se lo
**da** al que recibe (`give()`). Esto evita que el dinero "se pierda" o "se duplique" si algo
falla a medio camino: o se mueve completo, o no se mueve nada.

El comando en sí (`PayCommand.java`) añade validaciones antes de siquiera intentar el pago: no
puedes pagarte a ti mismo (línea 39-42), y si `pay()` devuelve que no había saldo suficiente, se
avisa al jugador con un sonido de fallo en vez de silencio (`TransactionSounds.failure`, línea
46). Si todo sale bien, tanto el que paga como el que recibe reciben un mensaje confirmando el
movimiento (líneas 51,56), y quien recibe el pago se registra por nombre (`trackName`, línea 50)
para que aparezca correctamente en `/baltop` aunque nunca antes hubiera tenido saldo.

Una decisión de diseño clave: `/pay` usa `give()`, no `giveEarned()` — quien recibe el pago **no**
gana experiencia de nivel por ello (ver [salarioDiario.md](salarioDiario.md)). Si diera XP, dos
jugadores podrían subir de nivel indefinidamente pasándose la misma moneda de un lado a otro sin
generar riqueza real.

## Cómo se conecta con otras features

Es el ejemplo más simple de cómo casi todo el mod comparte el mismo `EconomyManager` como única
fuente de verdad del dinero (ver [saldoYRanking.md](saldoYRanking.md)) — el mismo patrón
"validar todo, luego mutar" que usa `/pay` aquí se repite en `/trade`, las suscripciones y las
tiendas.
