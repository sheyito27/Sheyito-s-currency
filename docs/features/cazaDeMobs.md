# Caza de mobs opcional

**Estado:** implementado (desactivado por defecto).
**Código relacionado:** `MobKillListener.java`, `MobRewardsConfig.java`.
**Patrones:** [config](patronConfig.md).

## Qué es esto

Whitelist configurable de criaturas que, al morir a manos de un jugador, pagan una cantidad fija
de Sheyicoins.

## Cómo funciona

`MobKillListener.onLivingDeath` escucha `LivingDeathEvent` (se dispara con cualquier muerte) y
comprueba, en este orden: `config.enabled` (falso por defecto), que la víctima no sea otro
jugador, y que su tipo esté en `mobs.json` (`MobRewardsConfig.java:16-47`, más de 25 entidades
predefinidas de 3 SC a 1000 SC).

**Quién cobra:** `resolveKiller()` (`MobKillListener.java:71-81`) — golpe directo de un jugador
siempre cuenta; el golpe de una mascota domesticada (lobo, gato) solo cuenta si
`requireDirectPlayerKill = false` (por defecto `true`, solo cuenta el golpe directo).

**Por qué corta en lado cliente:** `LivingDeathEvent` también puede dispararse en el cliente en
ciertos casos. `ConfigManager.load()` solo se llama al arrancar un *servidor*, así que en un
cliente puro la config sería `null`; el listener corta explícitamente si detecta lado cliente
(línea 34) y además comprueba `config == null` (línea 39) — dos capas para el mismo caso, por si
alguien instala el mod (pensado solo para servidor) también en su cliente.

## Cómo se conecta con otras features

El pago usa `giveEarned()`, así que cuenta como XP hacia el [salario diario](salarioDiario.md).
