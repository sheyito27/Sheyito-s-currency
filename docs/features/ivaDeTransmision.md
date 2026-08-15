# IVA de transmisión

**Estado:** implementado.
**Código relacionado:** `TransmissionTaxConfig.java`, `EconomyManager.java` (`grossWithTax`/`netAfterTax`), `PayCommand.java`, `TradeSession.java`, `ShopTransactionService.java`, `ShopTradeListener.java`, `SubscriptionManager.java`.
**Patrones:** [config](patronConfig.md).

## Qué es esto

Toda transacción de compraventa entre partes — `/pay`, el dinero de `/trade`, comprar o vender en
una tienda de cartel, y cada cobro (inicial y recurrente) de una suscripción — quema un porcentaje
configurable (`taxPercent`, 10% por defecto) en ambos lados a la vez: el pagador paga de más, el
receptor recibe de menos. Es un **"doble corte"**, no un impuesto de un solo lado:

| Precio del cartel | Paga el comprador | Recibe el vendedor | Quemado en total |
|---|---|---|---|
| 100 SC | 110,00 SC (+10%) | 90,00 SC (-10%) | 20,00 SC (20%) |

El importe que escribís en `/pay` o el precio de un cartel es siempre el **precio pactado/sticker**
— nunca cambia, es lo que ambas partes ven y acuerdan. El IVA se calcula en caliente sobre ese
precio en el momento exacto del cobro, no se guarda ya aplicado en ningún sitio.

**Explícitamente fuera de alcance:** `/eco give|take|set` (ajustes de admin), `/sc reward`
(recompensas de misión), salario diario, caza de mobs, y los sumideros de sistema ya existentes
(peaje de [waystones](peajeMovilidadWaystones.md), [desbloqueo de dimensiones](desbloqueoDimensiones.md),
[renta de chunks](rentaDeChunks.md)) — ninguno de estos es una compraventa entre dos jugadores, así
que ninguno pasa por el IVA.

## Cómo funciona

Dos primitivas puras en `EconomyManager`, sin efectos secundarios (solo leen
`ConfigManager.transmissionTax()`, no tocan ningún saldo):

```java
public double grossWithTax(double price) {
    TransmissionTaxConfig config = ConfigManager.transmissionTax();
    if (!config.enabled) return price;
    return Money.round(price * (1 + config.taxPercent));
}

public double netAfterTax(double price) {
    TransmissionTaxConfig config = ConfigManager.transmissionTax();
    if (!config.enabled) return price;
    return Money.round(price * (1 - config.taxPercent));
}
```

No existe un método "transfer" compartido que las cuatro rutas de dinero usen en común: cada una ya
tenía su propia forma de `take()` + `give()`/`giveEarned()` (con su propio manejo de fallos —
tiendas revierte el retiro del cofre si el cobro falla, suscripciones usa `giveEarned` para que el
receptor gane XP, `/pay`/`/trade` usan `give` a secas para no poder farmear nivel moviendo dinero
entre alts). Forzar una única función de transferencia hubiera encajado mal en las cuatro, así que
cada sitio simplemente envuelve su `take`/`price` con `grossWithTax(price)` y su `give`/`price` con
`netAfterTax(price)`, tal como ya hacía antes con el precio a secas:

- **`EconomyManager.pay()`** — el primitivo P2P de `/pay`, ya taxado dentro de la propia función.
- **`TradeSession.complete()`** — la pata de dinero de un trade (siempre de `uuidA`, quien invita, a
  `uuidB`, quien acepta) aplica `grossWithTax` al `take` y `netAfterTax` al `give`.
- **`ShopTransactionService.buy()`/`.sell()`** — ambas direcciones de una tienda de cartel (el
  jugador compra de la tienda, o la tienda le compra al jugador) son simétricas: quien paga paga de
  más, quien recibe recibe de menos. El pre-check de saldo (antes de tocar el cofre) también compara
  contra el bruto, para no retirar-y-reembolsar el ítem innecesariamente cuando el saldo alcanza el
  precio pero no el precio+IVA.
- **`SubscriptionManager.subscribe()`/`.processDueCharges()`** — el primer cobro (al aceptar) y cada
  renovación aplican el IVA por igual. El precio guardado en la suscripción (`sub.price`) es siempre
  el pactado sin IVA — si el admin cambia `taxPercent` mientras una suscripción está activa, el
  siguiente cobro ya usa la tasa nueva.

Los mensajes de confirmación de las cuatro rutas muestran el importe real (bruto pagado / neto
recibido), no el precio pactado a secas, para que ninguna parte se sorprenda con su saldo.

## Comandos

No añade comandos propios; el único ajuste posible es `taxPercent` (y `enabled`) en
`config/sheyitoscurrency/transmission_tax.json`.

## Cómo se conecta con otras features

Es ortogonal a todo lo demás: no usa `take()`/`give()` directamente, sino que envuelve el precio que
cada feature ya le pasaba a esos métodos. `EconomyManager.charge()` (saldo negativo, sin comprobar
fondos) sigue sin tocarse — ninguna de las cuatro rutas taxadas lo usa, todas bloquean la
transacción si no hay fondos para el bruto.
