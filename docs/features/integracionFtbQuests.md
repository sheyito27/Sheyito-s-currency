# Recompensa automática de FTB Quests

**Estado:** implementado.
**Código relacionado:** `FTBQuestsCompat.java`, `FtbQuestsIntegration.java`, `EconomicMasterCommand.java`, `QuestRewardsConfig.java`.

## Qué es esto

FTB Quests es un mod de misiones muy usado en modpacks grandes. Este mod se integra con él de dos
formas distintas: automáticamente (toda misión completada paga sola, sin que el creador del
modpack tenga que configurar nada por misión) y manualmente (un comando que se puede pegar como
recompensa específica de una misión concreta, si se quiere un pago distinto al automático).

## Cómo funciona

**El problema a resolver: una dependencia opcional.** FTB Quests no siempre está instalado — el
mod debe funcionar igual de bien con o sin él, sin crashear si falta. Minecraft/Java cargan y
verifican el bytecode de una clase la primera vez que se la referencia; si esa clase usa tipos de
FTB Quests que no existen porque el mod no está instalado, el servidor crashearía con un
`NoClassDefFoundError` en cuanto se tocara esa clase. La solución es aislar **todo** el código que
menciona clases de FTB Quests en una única clase, `FtbQuestsIntegration` — y solo tocarla si antes
se confirmó que FTB Quests sí está cargado.

Esa comprobación vive en `FTBQuestsCompat.logDetection()` (`FTBQuestsCompat.java:21-27`), que se
ejecuta una vez al arrancar el mod (enganchado a `FMLCommonSetupEvent` en `EconomicMaster.java:41`).
Si `ModList.get().isLoaded("ftbquests")` es verdadero, delega en `FtbQuestsIntegration.register()`
— si es falso, ni siquiera se menciona esa clase, así que Java nunca intenta cargarla y no hay
riesgo de crash.

**El pago automático.** `FtbQuestsIntegration.register()` (`FtbQuestsIntegration.java:27-44`) se
suscribe al evento propio de FTB Quests que se dispara cuando **cualquier** misión se completa
(`ObjectCompletedEvent.QUEST`). Por cada jugador del equipo que completó la misión
(`event.getOnlineMembers()`), le da la misma cantidad fija configurada en `quests_rewards.json`
(`amount`, 10 SC por defecto) usando `giveEarned()`. Como es automático y aplica a **todas** las
misiones por igual, no hace falta configurar nada misión por misión.

**El pago manual (opcional).** Para servidores que quieran una recompensa distinta en una misión
concreta (más alta, por ejemplo, para la misión final), existe además
`/sheyitoscurrency reward <jugador> [monto]` (`EconomicMasterCommand.java`), pensado para pegarse
como recompensa de tipo "Comando" dentro de una misión específica de FTB Quests — cuando esa
misión se completa, FTB Quests ejecuta este comando él mismo. Si no se indica un monto, usa el
mismo `amount` configurado; si se indica uno, lo sobreescribe solo para esa llamada. Este comando
funciona **aunque FTB Quests no esté instalado** — sirve para que un admin dé recompensas a mano
desde consola en cualquier caso.

## Cómo se conecta con otras features

Ambos caminos de pago usan `giveEarned()`, así que las recompensas de misión cuentan como XP hacia
el [salario diario](salarioDiario.md), igual que la [caza de mobs](cazaDeMobs.md) o las
[tiendas](tiendasAutomaticas.md).
