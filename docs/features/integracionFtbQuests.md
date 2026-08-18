# Recompensa automática de FTB Quests

**Estado:** implementado.
**Código relacionado:** `FTBQuestsCompat.java`, `FtbQuestsIntegration.java`, `EconomicMasterCommand.java`, `QuestRewardsConfig.java`.
**Patrones:** [comandos](patronComandos.md) (`EconomicMasterCommand`), [config](patronConfig.md).

## Qué es esto

Integración con FTB Quests (mod de misiones) en dos vías: automática (toda misión completada
paga sola) y manual (comando pegable como recompensa de una misión concreta, para un importe
distinto al automático).

## Cómo funciona

**Dependencia opcional sin crash:** FTB Quests no siempre está instalado. Referenciar sus clases
sin comprobar antes causaría `NoClassDefFoundError` si falta. Todo el código que menciona tipos de
FTB Quests vive aislado en `FtbQuestsIntegration` (paquete-privada), y solo se toca tras confirmar
`ModList.get().isLoaded("ftbquests")` en `FTBQuestsCompat.logDetection()`
(`FTBQuestsCompat.java:21-27`, enganchado a `FMLCommonSetupEvent` en `EconomicMaster.java:41`) —
si falta el mod, esa clase nunca se referencia y Java nunca intenta cargarla.

**Pago automático:** `FtbQuestsIntegration.register()` (línea 27-44) se suscribe a
`ObjectCompletedEvent.QUEST` (evento propio de FTB Quests, cualquier misión completada). Da el
`amount` fijo de `quests_rewards.json` (10 SC por defecto) a cada miembro online del equipo
(`event.getOnlineMembers()`) vía `giveEarned()` — sin configuración por misión.

**Pago manual:** `/sc reward <jugador> [monto]` (`EconomicMasterCommand.java`),
pensado para pegarse como recompensa tipo "Comando" en una misión puntual. Sin `monto` usa el
`amount` configurado; con `monto` lo sobreescribe solo para esa llamada. Funciona con o sin FTB
Quests instalado.

## Cómo se conecta con otras features

Ambas vías usan `giveEarned()`: cuentan como XP hacia el [salario diario](salarioDiario.md), igual
que la [caza de mobs](cazaDeMobs.md) (y a diferencia de las [tiendas](tiendasAutomaticas.md), que
no dan XP).
