# Tiendas de cartel y cofre

**Estado:** implementado.
**Código relacionado:** `ShopManager.java`, `ShopSignParser.java`, `ShopCreationTracker.java`, `ShopTradeListener.java`, `ShopTransactionService.java`, `ShopProtectionListener.java`, `ShopConfig.java`.

## Qué es esto

Un jugador puede montar una tienda automática en el mundo con solo un cartel y un cofre pegados:
el cartel anuncia qué vende (o compra) y a qué precio, el cofre guarda el stock, y cualquier
jugador puede hacer clic en el cartel para comprar o vender **sin que el dueño tenga que estar
conectado**.

## Cómo funciona

**Crear una tienda escribiendo un cartel.** El formato del cartel son 3 líneas: tu nombre de
jugador (para que el mod compruebe que el cartel es tuyo), `SELL <precio>` o `BUY <precio>`, y
`<cantidad> <id_del_item>` (p. ej. `64 minecraft:diamond`). Minecraft no avisa cuando alguien
termina de escribir un cartel, así que el mod tiene que **vigilar por su cuenta**: cuando detecta
que se colocó un cartel, lo apunta como "pendiente" (`ShopCreationTracker.onSignPlaced`, línea
40-54) y cada tick comprueba si el jugador ya terminó de editarlo (`SignBlockEntity.getPlayerWhoMayEdit()`
se vacía solo cuando termina, se aleja o se desconecta — línea 86-113). Solo entonces se intenta
leer el texto (`ShopSignParser.parse`) y registrar la tienda si hay **exactamente un cofre**
pegado al cartel (`ShopContainers.findAdjacentChest`).

**Comprar o vender haciendo clic.** Cuando alguien (que no sea el dueño) hace clic derecho en un
cartel de tienda válido, `ShopTradeListener` intercepta el clic (cancela la acción normal de leer
el cartel) y delega en `ShopTransactionService`, que implementa las dos direcciones:

- `SELL` → la tienda **vende**: se comprueba que haya stock suficiente en el cofre, que el
  comprador tenga saldo, y que le quepa en el inventario — **en ese orden**, y solo si las tres
  cosas se cumplen se mueve nada (mismo patrón "validar todo, luego mutar" que ya viste en
  `/pay`).
- `BUY` → la tienda **compra**: se comprueba que el vendedor tenga los ítems, que el **dueño de
  la tienda** tenga saldo suficiente para pagarle, y que quepan en el cofre.

Cada resultado posible (sin stock, sin saldo, inventario lleno, cofre desaparecido...) tiene su
propio mensaje de error específico (`ShopTransactionService.Result`, `ShopTradeListener.java:51-78`)
en vez de un genérico "no se pudo".

**Protección.** Nadie salvo el dueño (o un admin) puede abrir el cofre de una tienda directamente
(`ShopProtectionListener.onRightClickChest`) — si se pudiera, el cartel sería decorativo y
cualquiera podría vaciar el cofre sin pagar. Tampoco se puede romper el cartel ni el cofre sin ser
el dueño (`onBreak`, línea 46-76); si se rompe legítimamente, la tienda se da de baja del
registro.

## Cómo se conecta con otras features

El dinero cobrado por una venta usa `giveEarned()` (cuenta como XP hacia el
[salario diario](salarioDiario.md)) tanto para el vendedor en `SELL` como para quien vende sus
ítems a la tienda en `BUY`. Las tiendas persisten en su propio `shops.json`
(`ShopManager.java:37`), independiente del saldo (`EconomyManager`) — solo guardan la ubicación,
dueño, acción, precio e ítem de cada cartel, no el stock (el stock vive físicamente en el cofre
del mundo).
