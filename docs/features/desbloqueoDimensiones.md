# Desbloqueo de dimensiones

**Estado:** implementado.
**Código relacionado:** `DimensionUnlockListener.java`, `DimensionUnlockManager.java`, `DimensionUnlockData.java`, `DimensionUnlockConfig.java`, `DimensionCommand.java`.
**Patrones:** [manager](patronManager.md), [config](patronConfig.md), [comandos](patronComandos.md).

## Qué es esto

Viajar a cualquier dimensión que no sea el Overworld (Nether, End, o cualquier dimensión modded)
cuesta `price` Sheyicoins (5000 por defecto) **la primera vez**. Si no tenés saldo suficiente, el
viaje se bloquea y te quedás en el Overworld, con un aviso. Si pagás, esa dimensión queda
desbloqueada para siempre — nunca más se te vuelve a cobrar por entrar a ella.

No hay nada hardcodeado por dimensión: la feature detecta genéricamente **cualquier** dimensión
que no sea el Overworld, así que Nether y End quedan cubiertos automáticamente hoy, y cualquier
dimensión que añada otro mod queda cubierta sin tocar código.

## Cómo funciona

**Un evento propio de NeoForge, no de un mod externo:** a diferencia del [peaje de
Waystones](peajeMovilidadWaystones.md), viajar de dimensión vía portal (Nether, End, o cualquier
portal modded que use el mecanismo estándar) dispara
`net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent` — un evento del propio mod
loader, siempre presente, sin necesidad de aislar imports `compileOnly` ni comprobar si algún mod
está cargado. Es `ICancellableEvent`: cancelarlo evita el viaje sin más.

```java
static boolean handleTravel(EconomyManager economy, DimensionUnlockManager unlocks, DimensionUnlockConfig config,
                             ServerPlayer player, ResourceKey<Level> targetDimension) {
    if (targetDimension.equals(Level.OVERWORLD)) {
        return true; // el Overworld siempre es gratis
    }
    if (unlocks.isUnlocked(player.getUUID(), targetDimension)) {
        return true; // ya pagado antes
    }
    if (!economy.take(player.getUUID(), config.price)) {
        return false; // no alcanza - se cancela el evento, el jugador se queda donde estaba
    }
    unlocks.unlock(player.getUUID(), targetDimension);
    return true; // primera vez que paga - se deja pasar este viaje
}
```

Para un portal Nether/End estándar el jugador ya está parado en el Overworld al intentar viajar,
así que cancelar el evento lo deja exactamente ahí — no hace falta teletransportarlo de vuelta a
mano.

**El mensaje muestra qué dimensión es, en color morado**, sin hardcodear nombres: usa la clave de
traducción vanilla `dimension.<namespace>.<path>` (ej. `dimension.minecraft.the_nether` → "The
Nether"), coloreada con `ChatFormatting.LIGHT_PURPLE`. Cualquier dimensión modded que registre esa
misma clave en su lang file también sale con nombre bonito; si no la registra, el cliente cae de
vuelta a mostrar la clave cruda en vez de romper algo.

**`DimensionUnlockManager`** sigue el [patrón de manager con ciclo de vida](patronManager.md):
persiste, por jugador, el conjunto de dimensiones ya pagadas (`dimension_unlocks.json`, dentro del
save del mundo). Igual que el resto de managers de este mod, el mapa en memoria usa `UUID` real
como key (`Map<UUID, Set<String>>`); la conversión a `String` (el id de la dimensión, ej.
`"minecraft:the_nether"`) solo pasa en `load()`/`save()`, nunca la ve Gson directamente.

## Comandos

- `/dimension lock <jugador> <dimension>` (OP nivel 2) — revierte el desbloqueo de esa dimensión
  para ese jugador, sin reembolsar el precio pagado. Es una herramienta de pruebas: deja re-probar
  el flujo de bloqueo/cobro sin tener que reiniciar el mundo. Usa `DimensionArgument` (el mismo
  argumento vanilla que `/execute in <dimension>`), así que autocompleta con las dimensiones
  cargadas.

El único ajuste de config es `price` (y `enabled`) en `config/sheyitoscurrency/dimension_unlock.json`.

## Cómo se conecta con otras features

Usa `take()`, igual que `/pay`, `/trade`, las tiendas o el [peaje de
Waystones](peajeMovilidadWaystones.md): bloquea la acción si no hay fondos, nunca deja saldo
negativo. Queda fuera del circuito de XP.
