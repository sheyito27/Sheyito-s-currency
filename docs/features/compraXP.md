# `/buy xp` — comprar experiencia de Minecraft

**Estado:** implementado.
**Código relacionado:** `BuyCommand.java`, `XpShopConfig.java`, `EconomyManager.take()`.

## Qué es esto

Un jugador puede cambiar Sheyicoins por **experiencia de verdad de Minecraft** — la barra verde
que usas para encantar objetos en la mesa de encantamientos o el yunque. `/buy xp <cantidad>`
paga el precio configurado y te da esa cantidad de puntos de experiencia al instante.

**Importante para no confundirlo:** esto **no tiene nada que ver** con el nivel/XP interno de
este mod (el que decide tu [salario diario](salarioDiario.md)). Son dos sistemas totalmente
distintos que por casualidad se llaman parecido:

| | XP del salario (mod) | XP de `/buy xp` (vanilla) |
|---|---|---|
| ¿Dónde vive? | `EconomyManager.xp` (interno del mod) | Datos propios del jugador en Minecraft |
| ¿Para qué sirve? | Decide tu nivel de salario | Encantar objetos, reparar en el yunque |
| ¿Cómo se sube? | Ganando dinero (`giveEarned`) | Jugando normal, o comprándola aquí |
| Curva de dificultad | Fibonacci (del mod) | La curva propia de Minecraft |

## Por qué existe

`docs/DESIGN.md` clasifica "Compra de XP" como una **válvula** — un sitio donde el dinero
acumulado se "quema" a cambio de algo de valor real, no como una conveniencia barata. La tasa se
fijó deliberadamente a `1 Sheyicoin = 1 punto de XP` en vez de una tasa más generosa, para que
comprar niveles altos de encantamiento cueste una parte real de varios días de ahorro, en vez de
ser gratis con las primeras monedas que consigas.

## Cómo funciona

Minecraft tiene su propia curva de experiencia, no relacionada con este mod: cada nivel de
jugador exige más puntos que el anterior (por ejemplo, llegar al nivel 30 exige 1.395 puntos
acumulados en total). `/buy xp` no "sabe" nada de niveles — solo vende **puntos sueltos**; es el
propio juego el que decide en qué nivel te deja esa cantidad de puntos.

El flujo del comando (`BuyCommand.java`), siguiendo el mismo patrón "validar todo, luego mutar"
que ya usan `/pay` y las tiendas:

1. Calcula el precio: `cantidad de puntos × coinsPerXpPoint` (configurable en `xp_shop.json`,
   `1.0` por defecto).
2. Intenta cobrar ese precio con `EconomyManager.take()` — si no hay saldo suficiente, el comando
   falla con un mensaje claro y un sonido de error, sin dar ni un punto de XP.
3. Si el cobro tuvo éxito, le da al jugador esos puntos de experiencia directamente
   (`Player.giveExperiencePoints`, un método propio de Minecraft) y confirma con mensaje y sonido
   de éxito.

Una decisión deliberada: este comando **nunca** llama a `giveEarned()` — es gastar dinero, no
ganarlo, así que no genera XP del salario. Mismo criterio que ya usan `/pay` y las tiendas: solo
lo que realmente entra como ingreso nuevo cuenta para subir de nivel de salario.

## Por qué se llama `/buy xp` y no `/xp buy`

`buy` es el literal base a propósito, pensando en el futuro: si algún día se añade otra cosa
comprable con Sheyicoins (gacha, sobres misteriosos, lo que sea de `docs/proposals.md`), puede
vivir como `/buy <lo-que-sea>` sin tener que reestructurar comandos existentes.

## Cómo se conecta con otras features

Usa `EconomyManager.take()`, el mismo mecanismo de cobro que `/pay` y las tiendas. **No** se
conecta con el [salario diario](salarioDiario.md) — es la primera feature del mod que gasta
dinero sin ninguna relación con el sistema de niveles interno.
