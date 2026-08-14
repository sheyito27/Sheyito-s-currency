# `/trade` — intercambio seguro con GUI

**Estado:** implementado.
**Código relacionado:** `TradeCommand.java`, `TradeManager.java`, `TradeSession.java`, `TradeMenu.java`, `TradeScheduler.java`.
**Patrones:** [invitación pendiente](patronInvitacionPendiente.md), [manager con ciclo de vida](patronManager.md), [comandos](patronComandos.md), [validar luego mutar](patronValidarLuegoMutar.md).

## Qué es esto

Intercambio de ítems (y opcionalmente dinero) entre dos jugadores con ventana compartida tipo
cofre: ninguno puede llevarse lo del otro sin que el intercambio se complete para ambos a la vez.

## Cómo funciona

**Invitación:** sigue el [patrón de invitación pendiente](patronInvitacionPendiente.md)
(`TradeManager.invite`, `TradeManager.java:74-86`). El dinero ofrecido, si lo hay, se fija **en
ese momento** y no es negociable dentro de la ventana — solo los ítems lo son.

**Al aceptar:** `TradeManager.accept()` (`TradeManager.java:88-114`) crea una `TradeSession` y
abre una `TradeMenu` por jugador. Ambas ventanas apuntan a los mismos objetos `SimpleContainer`
(intercambiados: "mi fila" para uno es "su fila" para el otro), así que la sincronización de
inventario ya vanilla de Minecraft mantiene ambas vistas iguales sin red propia del mod.

**Confirmar:** tocar la oferta después de confirmar cancela la confirmación de ambos
(`TradeSession.onOfferMutated`, línea 161) — nadie puede confirmar y cambiar de última hora.
Con ambos confirmados (`isLocked()`, línea 102-104), una barra de progreso de ~3s
(`TradeSession.tick`, líneas 176-190) precede a la finalización, como margen para cancelar.

**Completar:** el dinero se cobra en este instante exacto (línea 199-220), no antes — es el único
punto que puede fallar tras la doble confirmación, si el saldo de quien ofrecía dinero cambió
mientras tanto. Si falla, el intercambio entero se cancela y los ítems vuelven a su dueño
original (nunca a medias — [validar luego mutar](patronValidarLuegoMutar.md)).

**Sin persistencia:** ver la excepción de `TradeManager` en el
[patrón de manager](patronManager.md) — una negociación en curso no sobrevive un reinicio.

## Cómo se conecta con otras features

Usa `EconomyManager.take()`/`give()` igual que [`/pay`](pagosP2P.md). `TradeScheduler` corre cada
tick (no cada ~30s como el scheduler económico general) porque la barra de progreso necesita
resolución fina.
