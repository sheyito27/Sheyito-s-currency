# Patrón: manager con ciclo de vida y autoguardado perezoso

Usado por: `EconomyManager`, `SalaryManager`, `SubscriptionManager`, `ShopManager`,
`DimensionUnlockManager`, `ChunkClaimManager` — y parcialmente `TradeManager` (mismo ciclo de
vida, pero sin persistencia, ver más abajo).

## Qué resuelve

Cada estado de jugadores (saldos, tiendas, suscripciones...) necesita un único punto de acceso
en memoria durante la partida, cargado una vez al arrancar y guardado sin perder datos al parar,
sin escribir a disco en cada mutación individual.

## Cómo funciona

**Ciclo de vida:** un campo `private static volatile Manager instance`, constructor privado,
`init(MinecraftServer server)` (llamado desde `ServerLifecycleHandler.onServerStarting`) crea la
instancia y llama a `load()`, `get()` la expone al resto del mod, `shutdown()` (desde
`onServerStopping`) guarda y limpia la instancia.

**Persistencia:** `load()` lee su archivo JSON en `<mundo>/sheyitoscurrency/` con
`JsonFileUtil.loadOrCreate`; cada mutación marca un `AtomicBoolean dirty = true` en vez de guardar
al instante. `EconomicMasterScheduler` (cada 600 ticks, ~30s) llama a `saveIfDirty()` en todos los
managers — así una granja de mobs o un chat muy activo no generan una tormenta de escrituras a
disco por cada pequeño cambio; en el peor caso, un crash pierde como mucho unos segundos de
actividad.

**Excepción — `TradeManager`:** sigue el mismo `init/get/shutdown`, pero nunca implementa
`load()`/`save()` — un intercambio en curso es una negociación en vivo, no estado que tenga
sentido recuperar tras reiniciar el servidor (ver [tradeSeguro.md](tradeSeguro.md)).

**Soporte de tests:** todos exponen además `createForTesting()` (instancia en memoria que nunca
toca disco) e `installForTesting()`, para que los tests ejerciten la lógica real sin un servidor
corriendo.
