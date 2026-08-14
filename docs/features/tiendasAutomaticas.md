# Tiendas de cartel y cofre

**Estado:** implementado.
**Código relacionado:** `ShopManager.java`, `ShopSignParser.java`, `ShopCreationTracker.java`, `ShopTradeListener.java`, `ShopTransactionService.java`, `ShopProtectionListener.java`, `ShopConfig.java`.
**Patrones:** [validar luego mutar](patronValidarLuegoMutar.md), [manager con ciclo de vida](patronManager.md), [config](patronConfig.md).

## Qué es esto

Tienda automática con un cartel y un cofre pegados: el cartel anuncia qué vende (o compra) y a
qué precio, el cofre guarda el stock, cualquier jugador compra/vende con clic derecho sin que el
dueño esté conectado.

## Cómo funciona

**Crear una tienda:** formato del cartel en 3 líneas: nombre del jugador (verificación de
propiedad), `SELL <precio>` o `BUY <precio>`, `<cantidad> <id_del_item>`. Minecraft no avisa
cuando alguien termina de editar un cartel, así que `ShopCreationTracker` vigila: al colocarse un
cartel lo marca "pendiente" (línea 40-54), y cada tick comprueba si
`SignBlockEntity.getPlayerWhoMayEdit()` ya se vació (edición terminada) para recién entonces
parsear el texto (`ShopSignParser.parse`) y registrar la tienda, si hay exactamente un cofre
adyacente (`ShopContainers.findAdjacentChest`).

**Comprar o vender:** `ShopTradeListener` intercepta el clic de un no-dueño sobre un cartel válido
y delega en `ShopTransactionService`, [validar-luego-mutar](patronValidarLuegoMutar.md) en ambas
direcciones:

- `SELL` → la tienda vende: stock suficiente → saldo del comprador → espacio en su inventario.
- `BUY` → la tienda compra: ítems del vendedor → saldo del **dueño** de la tienda → espacio en el
  cofre.

Cada fallo posible (sin stock, sin saldo, inventario lleno, cofre desaparecido) tiene su propio
mensaje (`ShopTransactionService.Result`, `ShopTradeListener.java:51-78`).

**Protección:** nadie salvo el dueño o un admin puede abrir el cofre directamente
(`ShopProtectionListener.onRightClickChest`) ni romper cartel/cofre (`onBreak`, línea 46-76); si
se rompe legítimamente, la tienda se da de baja.

## Cómo se conecta con otras features

El pago en ambas direcciones usa `EconomyManager.give()` — **no** `giveEarned()`
(`ShopTransactionService.java:53,79`) — así que comprar o vender en una tienda **no** genera XP
hacia el [salario diario](salarioDiario.md), a diferencia de la caza de mobs o las misiones.
Las tiendas persisten en `shops.json` (solo ubicación, dueño, acción, precio e ítem — el stock
vive físicamente en el cofre del mundo, no en este archivo).
