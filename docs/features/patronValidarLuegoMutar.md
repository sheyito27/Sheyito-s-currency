# Patrón: validar todo, luego mutar

Usado por: `EconomyManager.pay()`, [`/pay`](pagosP2P.md), [tiendas](tiendasAutomaticas.md)
(`ShopTransactionService`), [`/buy xp`](compraXP.md), [`/trade`](tradeSeguro.md)
(`TradeSession.complete`).

## Qué resuelve

Una transacción con varias condiciones (saldo suficiente, stock disponible, espacio en
inventario...) no puede quedar a medias si una de las condiciones falla después de que otra ya
se aplicó.

## Cómo funciona

Todas las comprobaciones que pueden fallar se hacen primero, contra el estado real en ese
instante — y solo si **todas** pasan se ejecuta cualquier `take()`/`give()`/movimiento de ítems.
Como cada transacción corre síncronamente dentro de un único tick del servidor, no hay ventana
para que otra transacción se cuele entre la validación y la mutación e invalide una comprobación
ya hecha.

Ejemplo mínimo (`EconomyManager.pay`): primero `take(origen, cantidad)` — que ya de por sí falla
sin mover nada si no hay saldo — y solo si eso tuvo éxito se llama a `give(destino, cantidad)`. En
`ShopTransactionService` la cadena es más larga (stock → saldo del comprador → espacio en
inventario) pero sigue el mismo orden: comprobar todo, mutar al final.
