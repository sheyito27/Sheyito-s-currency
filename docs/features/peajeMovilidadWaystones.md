# Peaje de movilidad (Waystones)

**Estado:** implementado.
**Código relacionado:** `WaystonesCompat.java`, `WaystonesIntegration.java`, `WaystoneTollLogic.java`, `WaystoneTollConfig.java`.
**Patrones:** [config](patronConfig.md); ver también [integración FTB Quests](integracionFtbQuests.md), del que copia el mecanismo de soft-dependency.

## Qué es esto

Usar un waystone del mod Waystones (si está instalado) cuesta `cost` Sheyicoins (100 por defecto).
Si no tienes saldo suficiente, **no se bloquea el teletransporte** — se cobra igual y el saldo
puede quedar negativo. No hay un estado de "deuda" separado para eso: un saldo negativo es
simplemente eso, se consulta con `/bal` igual que uno positivo.

## Cómo funciona

**Dependencia opcional sin crash:** Waystones no siempre está instalado, y sus eventos no pasan
por el `IEventBus` de NeoForge sino por **Balm**, la librería cross-loader de la que depende a la
fuerza. Todo tipo de Waystones/Balm referenciado en el mod vive aislado en `WaystonesIntegration`
(paquete-privada), y solo se toca tras confirmar `ModList.get().isLoaded("waystones")` en
`WaystonesCompat.logDetection()` (enganchado a `FMLCommonSetupEvent` en `EconomicMaster.java`,
igual que `FTBQuestsCompat`) — si falta el mod, esa clase nunca se referencia y Java nunca intenta
cargarla. La dependencia es `compileOnly` en `build.gradle` (nunca `implementation`): compila
contra las clases reales de Waystones/Balm pero no se empaqueta ni se exige en runtime.

**Un solo evento, sin bloquear nada:** `WaystonesIntegration.register()` se suscribe a
`WaystoneTeleportEvent.Complete` vía `Balm.getEvents().onEvent(...)` — llega después de cada
intento de teletransporte, con el resultado de si tuvo éxito. Si `getPrimaryResult()` indica éxito
y la víctima es un `ServerPlayer`, se delega en `WaystoneTollLogic.applyToll`:

```java
static void applyToll(EconomyManager economy, WaystoneTollConfig config, UUID uuid) {
    economy.charge(uuid, config.cost);
}
```

Se usa `charge()` — el mismo método sin comprobación de fondos que ya usa `/eco charge` — a
propósito: a diferencia de `take()` (que usan `/pay`, `/trade` o las tiendas), `charge()` nunca
falla por fondos insuficientes, así que el teletransporte de Waystones nunca queda a medias ni
bloqueado por el mod. La lógica de cobro vive en `WaystoneTollLogic` (sin imports de
Waystones/Balm) para poder testearla sin esas clases en el classpath de test.

## Comandos

No añade comandos propios; el único ajuste posible es `cost` (y `enabled`) en
`config/sheyitoscurrency/waystone_toll.json`.

## Cómo se conecta con otras features

Usa `charge()`, no `take()`, así que igual que la [penalización por muerte](penalizacionPorMuerte.md)
queda fuera del circuito de XP. Es el primer consumidor real de `EconomyManager.charge()` desde
que se simplificó esa penalización — toda la infraestructura de deuda trackeada que existía antes
(`DebtManager`, `/debt`) se borró por completo al construir esta feature: mostraba cualquier saldo
negativo como "deuda" sin plazo real, así que se decidió no reactivarla y dejar que un saldo
negativo sea, sin más, saldo negativo.
