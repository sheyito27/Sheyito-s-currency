# Suscripciones entre jugadores

**Estado:** implementado.
**Código relacionado:** `SubscribeCommand.java`, `SubscriptionManager.java`, `PlayerSubscription.java`, `SubscriptionsConfig.java`.

## Qué es esto

Un jugador puede ofrecer un servicio (un rango, un terreno en alquiler, lo que sea, es solo texto
descriptivo) y proponerle a otro que le pague una cuota recurrente por ello — como una
suscripción real, cobrada automáticamente cada cierto número de días de juego, hasta que se
cancele.

## Cómo funciona

**Quién propone qué.** Es importante no confundirse con quién paga a quién:
`/subscribe <jugador> <dinero> <tiempo>` lo ejecuta el que **va a cobrar**, proponiéndole al
`<jugador>` objetivo que sea quien pague (`SubscribeCommand.java:54-71`). Es una propuesta, no un
cobro — nada se descuenta todavía.

**Aceptar es lo único que activa el cobro.** Igual que en `/trade`, la propuesta solo queda
pendiente hasta que el pagador ejecuta `/subscribe accept`. Solo ahí se llama a
`SubscriptionManager.subscribe()` (`SubscriptionManager.java:179-197`), que cobra el **primer**
período de inmediato y crea el registro recurrente (`PlayerSubscription`). Si el pagador no tiene
saldo suficiente en ese momento, la propuesta no se consume — se queda pendiente para que pueda
reintentar tras conseguir dinero, en vez de tener que pedir que le vuelvan a invitar
(`SubscriptionManager.java:128-133`).

**Cobro recurrente.** Cada suscripción activa guarda cuándo le toca el próximo cobro
(`nextChargeGameDay`, en días de juego). El scheduler general del mod revisa cada ~30 segundos
reales si a alguna le toca cobrar (`SubscriptionManager.processDueCharges`, línea 220-257): si el
pagador tiene saldo, se cobra y se calcula la siguiente fecha; si no lo tiene, la suscripción se
**cancela automáticamente** y se avisa al pagador — no se acumula deuda ni se reintenta
indefinidamente.

**Ver y cancelar.** `/subscribe providers` lista a quién le pagas tú (para poder cancelar por
número con `/subscribe cancel <numero>`); `/subscribe clients` lista quién te paga a ti (esas no
las puedes cancelar tú, solo el que paga puede hacerlo — es su dinero).

## Cómo se conecta con otras features

Comparte con `/trade` el mismo patrón de "invitación pendiente con expiración, nada se mueve
hasta aceptar explícitamente" (ver [tradeSeguro.md](tradeSeguro.md)). El dinero cobrado por el
vendedor usa `giveEarned()`, así que **sí** cuenta como XP hacia el
[salario diario](salarioDiario.md) — cobrar suscripciones es una forma real de subir de nivel,
a diferencia de recibir un `/pay`.
