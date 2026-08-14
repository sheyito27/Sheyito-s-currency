# Caza de mobs opcional

**Estado:** implementado (desactivado por defecto).
**Código relacionado:** `MobKillListener.java`, `MobRewardsConfig.java`.

## Qué es esto

Una whitelist configurable de criaturas que, al morir a manos de un jugador, le pagan una
cantidad fija de Sheyicoins — pensado para servidores donde matar mobs (o defenderse de un
ataque) debería tener una pequeña recompensa económica.

## Cómo funciona

Cada vez que **cualquier** criatura del juego muere, Minecraft dispara un evento
(`LivingDeathEvent`). El mod escucha ese evento (`MobKillListener.onLivingDeath`) y hace una
cadena de comprobaciones antes de pagar nada: que el sistema esté activado en la config
(`enabled`, desactivado por defecto — hay que encenderlo a propósito), que la víctima no sea otro
jugador (nadie cobra por matar a otro jugador con este sistema), y que la criatura que murió esté
en la lista de recompensas configurada (`mobs.json` trae más de 25 mobs vanilla predefinidos con
precios ya puestos, desde 3 SC por un slime hasta 1000 SC por el Ender Dragon —
`MobRewardsConfig.java:16-47`).

**Quién cobra.** No siempre es obvio quién "mató" al mob — `resolveKiller()`
(`MobKillListener.java:71-81`) decide: si el golpe final vino directamente de un jugador, cobra
ese jugador. Si vino de una mascota domesticada (lobo, gato) y la config lo permite
(`requireDirectPlayerKill = false`), cobra el dueño de la mascota. Por defecto
`requireDirectPlayerKill` es `true`, así que solo cuenta el golpe directo del propio jugador.

**Un detalle de seguridad poco obvio.** El evento de muerte no es 100% exclusivo del servidor —
también puede dispararse en el lado del cliente en ciertas situaciones. Como este mod está
pensado para correr solo en el servidor, si alguien lo instalara por error también en su cliente,
la config nunca se habría cargado ahí (`ConfigManager.load()` solo se llama al arrancar un
servidor) y el mod fallaría al intentar leerla. Por eso el listener corta inmediatamente si
detecta que está en el lado cliente (línea 34) y comprueba que la config no sea nula antes de
usarla (línea 39) — dos capas de seguridad para el mismo problema.

## Cómo se conecta con otras features

El pago usa `giveEarned()`, así que cuenta como XP hacia el
[salario diario](salarioDiario.md) — cazar mobs es, indirectamente, una forma de subir de nivel
más rápido.
