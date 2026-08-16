# Renta de chunks (FTB Chunks)

**Estado:** implementado.
**Código relacionado:** `FTBChunksCompat.java`, `FtbChunksIntegration.java`, `ChunkClaimLogic.java`, `ChunkClaimRegistry.java`, `ChunkClaimConfig.java`, `RentConfig.java`, `ChunkCommand.java`.
**Patrones:** [manager](patronManager.md), [config](patronConfig.md), [comandos](patronComandos.md); ver también [integración FTB Quests](integracionFtbQuests.md), [peaje de movilidad (Waystones)](peajeMovilidadWaystones.md) y [renta progresiva sobre ganancias](rentaProgresiva.md) (comparten cadencia de 7 días y el archivo `rent.json`).

## Qué es esto

Reclamar un chunk con el mod FTB Chunks (si está instalado) cuesta Sheyicoins — pero a diferencia
de cualquier otro cobro de este mod, **el precio no es fijo ni configurable**: escala como `n^1.5`
por jugador. El chunk número `n` que reclamás (1º, 2º, 3º...) cuesta `1000 * n^1.5`:

| Chunk # | Coste |
|---|---|
| 1º | 1.000 |
| 2º | ~2.828 |
| 3º | ~5.196 |
| 5º | ~11.180 |
| 10º | ~31.623 |
| 20º | ~89.443 |

Se probaron dos exponentes antes de llegar a este: una cuadrática pura (`n²`) escalaba demasiado
rápido (10º chunk 100.000, 100º 10.000.000); una raíz cuadrada (`n^0.5`) apenas crecía (10º chunk
solo ~3.162, se sentía casi plano). `1.5` es el punto intermedio: cada chunk adicional cuesta
proporcionalmente más que el anterior (a diferencia de una lineal `n^1`, que sube parejo) sin
volverse inalcanzable en las primeras decenas de chunks.

Si no tienes saldo suficiente para el siguiente chunk, **el reclamo se bloquea** — FTB Chunks
muestra un mensaje explicando por qué y el chunk no queda reclamado. Por eso el precio vive
hardcodeado en `ChunkClaimLogic` en vez de en el JSON de config: solo `enabled` es ajustable.

Este mod **no implementa nada de protección ni de reclamo en sí** — eso es enteramente trabajo de
FTB Chunks. Aquí solo se cobra y se lleva la cuenta. El reclamo en sí sigue siendo un pago único
— la renta periódica (cada 7 días) solo existe para los chunks que además tienes **force-loaded**,
ver más abajo.

## Renta de force-load

Force-loadear un chunk (mantenerlo cargado aunque nadie esté cerca, función propia de FTB Chunks)
sigue siendo **gratis al activarlo** — el cobro es retroactivo, cada `intervalGameDays` días de
juego (7 por defecto, `rent.json`, compartido con la [renta progresiva](rentaProgresiva.md)):

```
renta = forceLoadRentBase * n^1.5
```

Misma forma que el precio de reclamar un chunk, pero con `forceLoadRentBase = 10` (no 1000) y
`n` = chunks que tenés force-loaded **ahora mismo** — a diferencia del precio de reclamo, aquí no
se suma 1: se cobra por lo que ya tenés cargado, no por "el siguiente".

**Se cobra también estando desconectado** (el cobro es por días de juego, igual que las
suscripciones — no depende de que el jugador esté online). Si no cubrís el total, se descargan
**todos** tus chunks force-loaded de golpe (todo-o-nada, confirmado con el usuario — sin
selección parcial de cuáles conservar). El saldo nunca queda negativo por esto: es un
bloqueo/penalización, no una vía hacia el [embargo por deuda](embargoDeudas.md).

**El descargo real solo se puede hacer con un `ServerPlayer` vivo** (hace falta para construir el
`CommandSourceStack` que `ClaimedChunk.unload(...)` exige) — si el jugador está offline en el
momento del cobro fallido, se marca un flag `pendingForceUnload` en `ChunkClaimRegistry` en vez de
intentar nada, y el descargo real se aplica en cuanto se reconecta
(`ServerLifecycleHandler.onPlayerLoggedIn` → `FTBChunksCompat.applyPendingForceUnload`).

**Resolver el equipo del jugador sin depender de FTB Teams**: `ChunkTeamData` de FTB Chunks
está atado a un "team" (que puede ser un equipo real o una cuenta individual) —
`ClaimedChunkManager.getOrCreateData(ServerPlayer)`, verificado contra el jar real de FTB Chunks,
resuelve ese `ChunkTeamData` directamente a partir de un `ServerPlayer`, sin que este mod necesite
importar ningún tipo de FTB Teams. `ChunkTeamData.getForceLoadedChunks()` da la lista de chunks a
descargar, y cada uno se descarga con `ClaimedChunk.unload(CommandSourceStack)`.

**`AFTER_LOAD`/`AFTER_UNLOAD`** (mismo Architectury `Event`, misma forma `(CommandSourceStack,
ClaimedChunk)` que `AFTER_CLAIM`/`AFTER_UNCLAIM`, verificado con `javap` contra el jar real) llevan
el recuento en vivo de chunks force-loaded en `ChunkClaimRegistry` — el primer `AFTER_LOAD` de un
jugador siembra su `lastForceLoadRentDay`, así que el primer cobro llega un intervalo completo
después de empezar a force-loadear, no de forma retroactiva.

**Aislamiento del soft-dependency respetado igual que en el resto de esta integración**:
`EconomicMasterScheduler`/`ServerLifecycleHandler` (siempre cargados, tenga o no FTB Chunks
instalado) nunca llaman a `FtbChunksIntegration` directamente — solo a `FTBChunksCompat`, que
comprueba `ModList.isLoaded("ftbchunks")` en cada llamada y es un no-op si no está.

## Cómo funciona

**Dependencia opcional sin crash**, mismo mecanismo que FTB Quests/Waystones: todo tipo de FTB
Chunks/FTB Library referenciado en el mod vive aislado en `FtbChunksIntegration` (paquete-privada),
solo tocada tras confirmar `ModList.get().isLoaded("ftbchunks")` en `FTBChunksCompat.logDetection()`
(enganchado a `FMLCommonSetupEvent`). La dependencia es `compileOnly` en `build.gradle`.

**Los eventos de FTB Chunks van por Architectury** (`dev.architectury.event.Event`), el mismo
sistema que ya usa la integración de FTB Quests — no hace falta añadir esa dependencia de nuevo.

**Aviso crítico de la propia documentación de FTB Chunks**: los eventos `BEFORE_*`
pueden dispararse para una operación **simulada** (por ejemplo, una comprobación en la UI de "¿se
podría reclamar esto?" sin que se llegue a reclamar de verdad) — su javadoc dice explícitamente
que el handler no debe mutar estado ahí. Por eso toda mutación (cobrar, incrementar o decrementar
el contador) pasa en los eventos `AFTER_*`, que solo se disparan cuando la operación fue real:

```java
// BEFORE_CLAIM: solo comprueba contra el recuento actual, nunca cobra ni lo modifica
private static CompoundEventResult<ClaimResult> onBeforeClaim(CommandSourceStack source, ClaimedChunk chunk) {
    ServerPlayer player = source.getPlayer();
    int alreadyClaimed = claims.getClaimCount(player.getUUID());
    if (!ChunkClaimLogic.canAfford(economy, config, player.getUUID(), alreadyClaimed)) {
        return CompoundEventResult.interruptTrue(ClaimResult.customProblem("Saldo insuficiente..."));
    }
    return CompoundEventResult.pass();
}

// AFTER_CLAIM: cobra segun el recuento actual, y solo si cobro bien, lo incrementa
private static void onAfterClaim(CommandSourceStack source, ClaimedChunk chunk) {
    int alreadyClaimed = claims.getClaimCount(player.getUUID());
    if (ChunkClaimLogic.chargeClaim(economy, config, player.getUUID(), alreadyClaimed)) {
        claims.incrementClaimCount(player.getUUID());
    }
}

// AFTER_UNCLAIM: decrementa el mismo recuento, sin reembolso
private static void onAfterUnclaim(CommandSourceStack source, ClaimedChunk chunk) {
    claims.decrementClaimCount(player.getUUID());
}
```

Mismo patrón de dos fases (comprobar/cobrar) que el peaje de Waystones (`Prepare`/`Complete`), con
`AFTER_UNCLAIM` sumado para mantener el recuento honesto.

**Bug corregido:** en la primera versión, el recuento solo subía — desreclamar un chunk no lo
bajaba. Un jugador que reclamaba y liberaba chunks repetidamente veía que el precio seguía
calculándose sobre el total histórico ("llevo 34 en total") en vez de los que tiene ahora mismo
("tengo 1"), así que podían cobrarle el precio del 35º chunk por algo que era, en la práctica, su
2º chunk activo. `AFTER_UNCLAIM` + `decrementClaimCount` lo arregla: el recuento es "cuántos chunks
tenés reclamados ahora", no un contador de por vida.

**Verificado contra el código real que consume el resultado** (no solo la documentación pública,
para no adivinar mal): `ChunkTeamDataImpl.claim()` de FTB Chunks hace
`ClaimedChunkEvent.BEFORE_CLAIM.invoker().before(source, chunk).object()` y solo mira
`result.isSuccess()` — el valor booleano de `interruptTrue`/`interruptFalse` no importa en
absoluto, solo el `ClaimResult` envuelto en el `CompoundEventResult`. `ClaimResult.customProblem(String)`
técnicamente espera una translation key, pero como el resto de este mod nunca usó lang files, se
le pasa directamente el mensaje en español — Minecraft muestra el texto crudo cuando no encuentra
traducción registrada (mismo truco que el nombre de dimensión en morado).

**El mensaje de bloqueo se mantiene deliberadamente corto** (`"Saldo insuficiente (2828 SC)."`, sin
pasar por `Money.format()` ni su nombre de moneda completo): ese texto se renderiza dentro del
propio panel de reclamo de FTB Chunks, que tiene muy poco ancho y no hace wrap — un mensaje largo
se corta a mitad de frase. El mensaje de cobro exitoso (chat normal, con mucho más espacio) sí usa
`Money.format()` completo.

**`ChunkClaimRegistry`** (renombrada de `ChunkClaimManager` al añadir la renta de force-load — es
la misma entidad con más campos, no dos fuentes de verdad para "chunks reclamados") sigue el
[patrón de manager con ciclo de vida](patronManager.md): persiste, por jugador, cuántos chunks
tiene reclamados **y** cuántos force-loaded ahora mismo, cuándo se le cobró la renta de force-load
por última vez, y si tiene un descargo pendiente (`chunk_claim_data.json`, mismo nombre de archivo
de siempre — deliberado, para no perder los recuentos ya guardados al actualizar). Todos los mapas
usan `UUID` en memoria y `String` solo en `load()`/`save()`, nunca visto por Gson directamente,
mismo criterio que el resto del mod. `decrementClaimCount`/`decrementLoadedCount` tienen piso en
0, así que desreclamar o descargar de más nunca deja un recuento en negativo.

Toda la decisión de negocio (`costFor`, `canAfford`, `chargeClaim`) vive en `ChunkClaimLogic` — sin
imports de FTB Chunks/FTB Library ni del manager — para poder testearla pasando el recuento como un
`int` cualquiera, sin depender de esas clases en el classpath de test.

## Comandos

Para reclamar/liberar chunks, ninguno propio — FTB Chunks ya tiene los suyos; el único ajuste
posible es `enabled` en `config/sheyitoscurrency/chunk_claim.json`, el precio en sí no es
configurable, a propósito.

- `/sc chunk reset <jugador>` (OP nivel 2) — pone el recuento de ese jugador a 0, sin reembolsar
  nada. Herramienta de pruebas (`ChunkCommand.java`) para re-probar la curva de precio desde el
  principio sin tener que desreclamar chunk a chunk; vive bajo la raíz compartida `/sc` (ver
  [patrón de comandos](patronComandos.md)).
- `/sc rent force <player>` (OP nivel 2) — fuerza el cobro de la renta de force-load de ese
  jugador ahora mismo, ignorando si ya pasaron `intervalGameDays` de verdad (mismo comando que
  fuerza la [renta progresiva sobre ganancias](rentaProgresiva.md), en una sola llamada). No-op
  si no tiene ningún chunk force-loaded — nada que cobrar.

## Cómo se conecta con otras features

Usa `take()`, igual que el peaje de Waystones o el desbloqueo de dimensiones: bloquea la acción si
no hay fondos, nunca deja saldo negativo. Queda fuera del circuito de XP.
