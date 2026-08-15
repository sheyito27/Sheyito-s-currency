# Renta de chunks (FTB Chunks)

**Estado:** implementado.
**Código relacionado:** `FTBChunksCompat.java`, `FtbChunksIntegration.java`, `ChunkClaimLogic.java`, `ChunkClaimConfig.java`.
**Patrones:** [config](patronConfig.md); ver también [integración FTB Quests](integracionFtbQuests.md) y [peaje de movilidad (Waystones)](peajeMovilidadWaystones.md), de los que copia el mecanismo de soft-dependency.

## Qué es esto

Reclamar un chunk con el mod FTB Chunks (si está instalado) cuesta `cost` Sheyicoins (200 por
defecto), **una sola vez por chunk**. Si no tienes saldo suficiente, **el reclamo se bloquea** —
FTB Chunks muestra un mensaje explicando por qué y el chunk no queda reclamado.

Este mod **no implementa nada de protección ni de reclamo en sí** — eso es enteramente trabajo de
FTB Chunks. Aquí solo se cobra. No hay renta periódica: eso queda para la futura feature separada
"Día de Renta" (`docs/proposals.md`), que cobraría chunks + cuotas + suscripciones + deudas juntos
con una cuenta regresiva — sin construirse todavía.

## Cómo funciona

**Dependencia opcional sin crash**, mismo mecanismo que FTB Quests/Waystones: todo tipo de FTB
Chunks/FTB Library referenciado en el mod vive aislado en `FtbChunksIntegration` (paquete-privada),
solo tocada tras confirmar `ModList.get().isLoaded("ftbchunks")` en `FTBChunksCompat.logDetection()`
(enganchado a `FMLCommonSetupEvent`). La dependencia es `compileOnly` en `build.gradle`.

**Los eventos de FTB Chunks van por Architectury** (`dev.architectury.event.Event`), el mismo
sistema que ya usa la integración de FTB Quests — no hace falta añadir esa dependencia de nuevo.

**Aviso crítico de la propia documentación de FTB Chunks**: `ClaimedChunkEvent.BEFORE_CLAIM`
puede dispararse para una operación **simulada** (por ejemplo, una comprobación en la UI de "¿se
podría reclamar esto?" sin que se llegue a reclamar de verdad) — su javadoc dice explícitamente
que el handler no debe mutar estado ahí. Por eso el cobro real pasa en `AFTER_CLAIM`, que solo se
dispara cuando el reclamo fue real:

```java
// BEFORE_CLAIM: solo comprueba, nunca cobra
private static CompoundEventResult<ClaimResult> onBeforeClaim(CommandSourceStack source, ClaimedChunk chunk) {
    ServerPlayer player = source.getPlayer();
    if (player == null || !ChunkClaimLogic.canAfford(economy, config, player.getUUID())) {
        return CompoundEventResult.interruptTrue(ClaimResult.customProblem("No tienes suficiente saldo..."));
    }
    return CompoundEventResult.pass();
}

// AFTER_CLAIM: cobra, solo si el reclamo fue real
private static void onAfterClaim(CommandSourceStack source, ClaimedChunk chunk) {
    ChunkClaimLogic.chargeClaim(economy, config, player.getUUID());
}
```

Mismo patrón de dos fases que el peaje de Waystones (`Prepare` comprueba, `Complete` cobra).

**Verificado contra el código real que consume el resultado** (no solo la documentación pública,
para no adivinar mal): `ChunkTeamDataImpl.claim()` de FTB Chunks hace
`ClaimedChunkEvent.BEFORE_CLAIM.invoker().before(source, chunk).object()` y solo mira
`result.isSuccess()` — el valor booleano de `interruptTrue`/`interruptFalse` no importa en
absoluto, solo el `ClaimResult` envuelto en el `CompoundEventResult`. `ClaimResult.customProblem(String)`
técnicamente espera una translation key, pero como el resto de este mod nunca usó lang files, se
le pasa directamente el mensaje en español — Minecraft muestra el texto crudo cuando no encuentra
traducción registrada (mismo truco que el nombre de dimensión en morado).

Toda la decisión de negocio (`canAfford`, `chargeClaim`) vive en `ChunkClaimLogic` — sin imports
de FTB Chunks/FTB Library — para poder testearla sin esas clases en el classpath de test.

## Comandos

No añade comandos propios (ni falta — FTB Chunks ya tiene los suyos para reclamar/liberar); el
único ajuste posible es `cost` (y `enabled`) en `config/sheyitoscurrency/chunk_claim.json`.

## Cómo se conecta con otras features

Usa `take()`, igual que el peaje de Waystones o el desbloqueo de dimensiones: bloquea la acción si
no hay fondos, nunca deja saldo negativo. Queda fuera del circuito de XP.
