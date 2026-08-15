# Renta de chunks (FTB Chunks)

**Estado:** implementado.
**Código relacionado:** `FTBChunksCompat.java`, `FtbChunksIntegration.java`, `ChunkClaimLogic.java`, `ChunkClaimManager.java`, `ChunkClaimConfig.java`.
**Patrones:** [manager](patronManager.md), [config](patronConfig.md); ver también [integración FTB Quests](integracionFtbQuests.md) y [peaje de movilidad (Waystones)](peajeMovilidadWaystones.md), de los que copia el mecanismo de soft-dependency.

## Qué es esto

Reclamar un chunk con el mod FTB Chunks (si está instalado) cuesta Sheyicoins — pero a diferencia
de cualquier otro cobro de este mod, **el precio no es fijo ni configurable**: escala con la raíz
cuadrada del número de chunk por jugador. El chunk número `n` que reclamás (1º, 2º, 3º...) cuesta
`1000 * √n`:

| Chunk # | Coste |
|---|---|
| 1º | 1.000 |
| 2º | ~1.414 |
| 3º | ~1.732 |
| 4º | 2.000 |
| 10º | ~3.162 |
| 100º | 10.000 |

(Se probó primero una cuadrática pura, `1000 * n²` — el 10º chunk salía 100.000 y el 100º
10.000.000, demasiado agresivo. La raíz cuadrada mantiene la fricción anti-acaparamiento sin
volverse inalcanzable a partir de unos pocos chunks.)

Si no tienes saldo suficiente para el siguiente chunk, **el reclamo se bloquea** — FTB Chunks
muestra un mensaje explicando por qué y el chunk no queda reclamado. Por eso el precio vive
hardcodeado en `ChunkClaimLogic` en vez de en el JSON de config: solo `enabled` es ajustable.

Este mod **no implementa nada de protección ni de reclamo en sí** — eso es enteramente trabajo de
FTB Chunks. Aquí solo se cobra y se lleva la cuenta. No hay renta periódica: eso queda para la
futura feature separada "Día de Renta" (`docs/proposals.md`), que cobraría chunks + cuotas +
suscripciones + deudas juntos con una cuenta regresiva — sin construirse todavía.

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
que el handler no debe mutar estado ahí. Por eso el cobro real — y el incremento del contador que
determina el precio del próximo chunk — pasan en `AFTER_CLAIM`, que solo se dispara cuando el
reclamo fue real:

```java
// BEFORE_CLAIM: solo comprueba contra el recuento actual, nunca cobra ni lo modifica
private static CompoundEventResult<ClaimResult> onBeforeClaim(CommandSourceStack source, ClaimedChunk chunk) {
    ServerPlayer player = source.getPlayer();
    int alreadyClaimed = claims.getClaimCount(player.getUUID());
    if (!ChunkClaimLogic.canAfford(economy, config, player.getUUID(), alreadyClaimed)) {
        return CompoundEventResult.interruptTrue(ClaimResult.customProblem("No tienes suficiente saldo..."));
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

**El mensaje de bloqueo se mantiene deliberadamente corto** (`"Saldo insuficiente (1414 SC)."`, sin
pasar por `Money.format()` ni su nombre de moneda completo): ese texto se renderiza dentro del
propio panel de reclamo de FTB Chunks, que tiene muy poco ancho y no hace wrap — un mensaje largo
se corta a mitad de frase. El mensaje de cobro exitoso (chat normal, con mucho más espacio) sí usa
`Money.format()` completo.

**`ChunkClaimManager`** sigue el [patrón de manager con ciclo de vida](patronManager.md): persiste,
por jugador, cuántos chunks lleva reclamados (`chunk_claim_data.json`, dentro del save del mundo) —
un `Map<UUID, Integer>` simple, mismo criterio que `SalaryManager` (conversión a `String` solo en
`load()`/`save()`, nunca la ve Gson directamente).

Toda la decisión de negocio (`costFor`, `canAfford`, `chargeClaim`) vive en `ChunkClaimLogic` — sin
imports de FTB Chunks/FTB Library ni del manager — para poder testearla pasando el recuento como un
`int` cualquiera, sin depender de esas clases en el classpath de test.

## Comandos

No añade comandos propios (ni falta — FTB Chunks ya tiene los suyos para reclamar/liberar); el
único ajuste posible es `enabled` en `config/sheyitoscurrency/chunk_claim.json` — el precio en sí
no es configurable, a propósito.

## Cómo se conecta con otras features

Usa `take()`, igual que el peaje de Waystones o el desbloqueo de dimensiones: bloquea la acción si
no hay fondos, nunca deja saldo negativo. Queda fuera del circuito de XP.
