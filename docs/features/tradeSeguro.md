# `/trade` — intercambio seguro con GUI

**Estado:** implementado.
**Código relacionado:** `TradeCommand.java`, `TradeManager.java`, `TradeSession.java`, `TradeMenu.java`, `TradeScheduler.java`.

## Qué es esto

Un intercambio de ítems (y opcionalmente dinero) entre dos jugadores, con una ventana compartida
tipo cofre, diseñado para que **nadie pueda estafar a nadie**: ninguno de los dos puede llevarse
lo del otro sin que el intercambio se complete de verdad para ambos a la vez.

## Cómo funciona

**La invitación.** `/trade <jugador> [dinero] [mensaje]` (`TradeCommand.java`) no abre ninguna
ventana todavía — solo registra una invitación pendiente en `TradeManager`
(`TradeManager.java:74-86`) y le manda al destino un mensaje con un botón clicable "[Aceptar]"
(usando un `ClickEvent` de Minecraft que ejecuta `/trade accept` por él). El dinero, si lo hay, se
fija **en este momento** y ya no se puede cambiar dentro de la ventana — solo los ítems son
negociables ahí dentro.

**Al aceptar.** `TradeManager.accept()` (`TradeManager.java:88-114`) crea una `TradeSession` —el
estado compartido del intercambio— y abre una ventana (`TradeMenu`) para cada jugador. Aquí está
el truco que hace que sea seguro sin escribir sincronización de red a mano: ambas ventanas
apuntan a los **mismos objetos contenedor** en memoria (`SimpleContainer` de Minecraft), solo que
intercambiados ("mi fila" para uno es "su fila" para el otro). Minecraft ya sincroniza
automáticamente cualquier cambio en un contenedor a todos los que tienen una ventana abierta sobre
él, así que ambos ven los mismos ítems en tiempo real sin que el mod tenga que enviar ningún
paquete de red manualmente.

**Confirmar y la barra de progreso.** Cada jugador tiene un botón para confirmar su oferta. Si
cualquiera de los dos **toca** su oferta después de confirmar, la confirmación se cancela
automáticamente para ambos (`TradeSession.onOfferMutated`, línea 161) — así nadie puede confirmar,
esperar a que el otro confirme, y cambiar su oferta en el último instante. Solo cuando **ambos**
están confirmados a la vez (`isLocked()`, línea 102-104) empieza a llenarse una barra de progreso
de ~3 segundos (`TradeSession.tick`, línea 176-190) antes de completar el intercambio de verdad —
un pequeño margen para que cualquiera pueda cancelar si se arrepiente en el último segundo.

**Al completar.** El dinero pactado se cobra en este momento exacto, no antes (línea 199-220) —
es la única parte del intercambio que puede fallar incluso después de que ambos confirmaron, si
el saldo del que ofrecía dinero cambió mientras tanto (p. ej. lo gastó en otra cosa a la vez). Si
eso pasa, el intercambio entero se cancela y todos los ítems vuelven a su dueño original — nunca
se completa "a medias".

**Nada se guarda en disco.** Toda la negociación vive solo en memoria mientras dura (`TradeManager`
y `TradeSession` no tocan `JsonFileUtil` en ningún momento) — es una negociación en vivo, no un
estado que tenga sentido recuperar tras un reinicio del servidor.

## Cómo se conecta con otras features

Usa `EconomyManager.take()`/`give()` igual que `/pay`, y comparte el mismo patrón de invitación
pendiente + expiración por tiempo que usan las [suscripciones](suscripcionesP2P.md)
(`TradeManager.INVITE_TIMEOUT_TICKS`). `TradeScheduler` corre cada tick del servidor (a diferencia
del scheduler económico general, que corre cada ~30s) porque la barra de progreso necesita
resolución fina.
