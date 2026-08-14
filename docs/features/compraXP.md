# `/buy xp` — comprar experiencia de Minecraft

**Estado:** implementado.
**Código relacionado:** `BuyCommand.java`, `XpShopConfig.java`, `EconomyManager.take()`.
**Patrones:** [comandos](patronComandos.md), [config](patronConfig.md), [validar luego mutar](patronValidarLuegoMutar.md).

## Qué es esto

`/buy xp <cantidad>` cambia Sheyicoins por puntos de XP **vanilla** (enchanting), no relacionada
con el nivel/XP interno del [salario diario](salarioDiario.md) — mismo nombre, sistemas
independientes:

| | XP del salario (mod) | XP de `/buy xp` (vanilla) |
|---|---|---|
| Dónde vive | `EconomyManager.xp` | datos propios del jugador en Minecraft |
| Para qué | decide el nivel de salario | encantar, yunque |
| Cómo sube | `giveEarned` | jugando, o comprándola aquí |

## Por qué existe

"Compra de XP" se diseñó como válvula (sink), no conveniencia barata. Tasa fijada
a `1 SC = 1 XP` (no una tasa más generosa) para que niveles altos de encantamiento cuesten una
parte real de varios días de ahorro.

## Cómo funciona

Minecraft tiene su propia curva de experiencia, independiente de este mod (nivel 30 = 1.395
puntos acumulados). `/buy xp` solo vende puntos sueltos — el juego decide en qué nivel te deja esa
cantidad.

`BuyCommand.java`, [validar-luego-mutar](patronValidarLuegoMutar.md):
1. `cost = cantidad × coinsPerXpPoint` (`xp_shop.json`, 1.0 por defecto).
2. `EconomyManager.take()` — si falla, mensaje + sonido de error, cero XP entregada.
3. Si cobra, `player.giveExperiencePoints(cantidad)` (vanilla) + mensaje/sonido de éxito.

Nunca llama a `giveEarned()`: gastar dinero no genera XP de salario (mismo criterio que
[`/pay`](pagosP2P.md) y las [tiendas](tiendasAutomaticas.md)).

## Por qué `/buy xp` y no `/xp buy`

`buy` es el literal base a propósito: otra cosa comprable en el futuro (`docs/proposals.md`)
puede vivir como `/buy <lo-que-sea>` sin reestructurar comandos existentes.

## Cómo se conecta con otras features

Usa `EconomyManager.take()`, mismo mecanismo que [`/pay`](pagosP2P.md) y las tiendas. No se
conecta con el sistema de niveles interno del mod.
