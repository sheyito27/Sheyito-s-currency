# Peaje de movilidad (Waystones)

**Estado:** implementado.
**Código relacionado:** `WaystonesCompat.java`, `WaystonesIntegration.java`, `WaystoneTollLogic.java`, `WaystoneTollConfig.java`.
**Patrones:** [config](patronConfig.md); ver también [integración FTB Quests](integracionFtbQuests.md), del que copia el mecanismo de soft-dependency.

## Qué es esto

Usar un waystone del mod Waystones (si está instalado) cuesta `cost` Sheyicoins (100 por defecto).
Si no tienes saldo suficiente, **se bloquea el teletransporte** — no se cobra nada y Waystones
muestra un error explicando por qué. El saldo negativo queda reservado para una futura feature de
pagos obligatorios; esta no lo usa.

## Cómo funciona

**Dependencia opcional sin crash:** Waystones no siempre está instalado, y sus eventos no pasan
por el `IEventBus` de NeoForge sino por **Balm**, la librería cross-loader de la que depende a la
fuerza. Todo tipo de Waystones/Balm referenciado en el mod vive aislado en `WaystonesIntegration`
(paquete-privada), y solo se toca tras confirmar `ModList.get().isLoaded("waystones")` en
`WaystonesCompat.logDetection()` (enganchado a `FMLCommonSetupEvent` en `EconomicMaster.java`,
igual que `FTBQuestsCompat`) — si falta el mod, esa clase nunca se referencia y Java nunca intenta
cargarla. La dependencia es `compileOnly` en `build.gradle` (nunca `implementation`): compila
contra las clases reales de Waystones/Balm pero no se empaqueta ni se exige en runtime.

**Dos fases, para bloquear sin arriesgar el cobro de XP vanilla:** Waystones no expone un
`setCanceled(true)` simple en sus eventos. La única forma documentada de fallar un
teletransporte es `WaystoneTeleportEvent.Prepare#addPreparationTask`, una tarea asíncrona que
puede resolver en un `WaystoneTeleportError`. Se usa esa fase solo para **comprobar** (nunca
cobra):

```java
event.addPreparationTask(prior -> {
    if (prior.right().isPresent()) {
        return CompletableFuture.completedFuture(prior); // ya fallo por otra razon, no pisarlo
    }
    if (WaystoneTollLogic.canAfford(economy, config, player.getUUID())) {
        return CompletableFuture.completedFuture(Either.left(null));
    }
    return CompletableFuture.completedFuture(Either.right(new WaystoneTeleportError(mensaje)));
});
```

El cobro real pasa en `WaystoneTeleportEvent.Complete`, y solo si `getPrimaryResult()` confirma
que el teletransporte tuvo éxito — así un jugador que podía pagar en el momento de `Prepare` nunca
queda cobrado por un teletransporte que termina fallando por otra razón (chunk que no carga, etc.):

```java
static boolean chargeToll(EconomyManager economy, WaystoneTollConfig config, UUID uuid) {
    return economy.take(uuid, config.cost); // requiere fondos suficientes, nunca deja saldo negativo
}
```

Se evitó a propósito la API de `WarpRequirement` de Waystones (el mecanismo "nativo" para costes
de teletransporte): es una composición no documentada y arriesgaba pisar el requisito de XP
vanilla si no se combinaba bien. El combo `Prepare` (chequear) + `Complete` (cobrar solo si hubo
éxito) es más simple y usa únicamente API pública documentada.

Toda la decisión de negocio (`canAfford`, `chargeToll`) vive en `WaystoneTollLogic` — sin imports
de Waystones/Balm — para poder testearla sin esas clases en el classpath de test.

## Comandos

No añade comandos propios; el único ajuste posible es `cost` (y `enabled`) en
`config/sheyitoscurrency/waystone_toll.json`.

## Cómo se conecta con otras features

Usa `take()`, igual que `/pay`, `/trade` o las tiendas: bloquea la acción si no hay fondos, en vez
de dejarla pasar con saldo negativo. Queda fuera del circuito de XP, igual que la
[penalización por muerte](penalizacionPorMuerte.md). `EconomyManager.charge()` (sin comprobar
fondos) sigue sin un consumidor real en el mod — `/eco charge` la mantiene viva para pruebas y
para una futura feature de pagos obligatorios.
