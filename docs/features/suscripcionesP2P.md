# Suscripciones entre jugadores

**Estado:** implementado.
**Código relacionado:** `SubscribeCommand.java`, `SubscriptionManager.java`, `PlayerSubscription.java`, `SubscriptionsConfig.java`.
**Patrones:** [invitación pendiente](patronInvitacionPendiente.md), [manager con ciclo de vida](patronManager.md), [comandos](patronComandos.md), [config](patronConfig.md).

## Qué es esto

Un jugador ofrece un servicio (texto libre, sin lógica asociada) y propone que otro le pague una
cuota recurrente por él, cobrada automáticamente cada cierto número de días de juego hasta que se
cancele.

## Cómo funciona

**Quién propone qué:** `/subscribe <jugador> <dinero> <tiempo>` lo ejecuta quien **va a cobrar**,
proponiéndole al `<jugador>` objetivo que sea quien pague (`SubscribeCommand.java:54-71`) — sigue
el [patrón de invitación pendiente](patronInvitacionPendiente.md).

**Aceptar activa el cobro:** solo al aceptar se llama `SubscriptionManager.subscribe()`
(`SubscriptionManager.java:179-197`), que cobra el primer período y crea el `PlayerSubscription`.
Si el pagador no tiene saldo, la propuesta **no se consume** — queda pendiente para reintentar
tras conseguir dinero (`SubscriptionManager.java:128-133`), en vez de exigir una nueva invitación.

**Cobro recurrente:** cada suscripción guarda `nextChargeGameDay`. El scheduler general
(`SubscriptionManager.processDueCharges`, línea 220-257) cobra las vencidas; si el pagador no
tiene saldo, la suscripción se cancela automáticamente y se avisa — sin deuda acumulada ni
reintentos.

**Ver y cancelar:** `/subscribe providers` (a quién pagas, cancelable por número con
`/subscribe cancel <numero>`); `/subscribe clients` (quién te paga — solo cancelable por quien
paga).

## Cómo se conecta con otras features

El cobro del vendedor usa `giveEarned()`, así que cuenta como XP hacia el
[salario diario](salarioDiario.md) — a diferencia de recibir un [`/pay`](pagosP2P.md).
