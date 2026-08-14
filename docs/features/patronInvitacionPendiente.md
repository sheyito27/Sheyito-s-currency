# Patrón: invitación pendiente con expiración

Usado por: [`/trade`](tradeSeguro.md), [suscripciones](suscripcionesP2P.md).

## Qué resuelve

Cualquier acuerdo entre dos jugadores (intercambiar ítems, pagar una cuota) necesita el
consentimiento explícito de ambos lados — nada se mueve solo porque uno de los dos lo propuso.

## Cómo funciona

Quien inicia (`/trade <jugador>`, `/subscribe <jugador> <precio> <intervalo>`) no mueve nada
todavía: solo registra una invitación pendiente en un `Map<UUID, Invite>` en memoria, keyed por el
UUID de a quién le toca aceptar (`TradeManager.pendingInvites`,
`SubscriptionManager.pendingInvites`), con un `expiresAtTick` calculado a partir del tick actual
del servidor. El destinatario recibe un mensaje con un botón `[Aceptar]` clicable (`ClickEvent`
que ejecuta `/trade accept` o `/subscribe accept` por él).

Nada ocurre hasta que el destinatario ejecuta `accept` explícitamente — ahí es donde de verdad se
crea el estado duradero (`TradeSession`, `PlayerSubscription`) y, en el caso de suscripciones, se
cobra el primer pago. Si el tiempo pasa sin que nadie acepte, un scheduler purga las invitaciones
vencidas comparando `expiresAtTick` contra el tick actual.

Una nueva invitación al mismo destinatario **sobreescribe** la anterior — no se acumulan
invitaciones pendientes duplicadas de la misma persona.
