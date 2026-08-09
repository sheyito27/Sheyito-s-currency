# Sheyito's currency

Mod **100% server-side** para NeoForge 1.21.1: economía virtual basada en comandos, `/baltop`, suscripciones jugador-a-jugador, salario diario con sistema de niveles, caza de mobs con whitelist configurable, intercambio seguro `/trade` con GUI tipo cofre, tiendas de cartel+cofre, e integración nativa con **FTB Quests** para recompensas monetarias por misión.

No registra bloques, ítems, pantallas ni nada renderizado en cliente: los clientes pueden conectarse al servidor sin instalar el mod.

## Requisitos de compilación

- JDK 21
- Conexión a internet la primera vez (Gradle descarga NeoForge/NeoGradle y las dependencias)

```bash
./gradlew build
```

El `.jar` resultante queda en `build/libs/sheyitoscurrency-1.0.0.jar`. Cópialo a la carpeta `mods/` del servidor (NeoForge 1.21.1, `neo_version` recomendado `21.1.248` o superior de la rama `21.1.x`). También funciona igual en un mundo de un solo jugador: es un servidor integrado, así que basta con poner el jar en los `mods/` del cliente.

## Persistencia

- **Configuración** (editable, se autogenera en el primer arranque): `config/sheyitoscurrency/`
  - `general.json` — símbolo de moneda, decimales, saldo inicial, opciones de aviso por chat.
  - `mobs.json` — whitelist de entidades y el dinero que dan al morir a manos de un jugador.
  - `salary.json` — salario diario y la curva de niveles (ver más abajo).
  - `quests_rewards.json` — un único importe fijo (`amount`, 50 por defecto) que paga toda misión de FTB Quests.
  - `subscriptions.json` — solo el intervalo de cobro (`intervalGameDays`, 5 días de juego por defecto). Las suscripciones en sí son 100% entre jugadores, no hay nada más que configurar aquí.
  - `shop.json` — tiempo límite en ticks para terminar de escribir un cartel de tienda (`pendingSignTimeoutTicks`, 600 = 30s por defecto).
- **Datos de jugadores** (saldos, XP/nivel, ofertas y suscripciones activas, últimos pagos, tiendas registradas): dentro de la carpeta del mundo, en `<mundo>/sheyitoscurrency/`. Viaja con la copia de seguridad del mundo.

## Comandos

### Jugadores (sin OP, todos públicos)
- `/bal` — muestra tu saldo.
- `/bal player <jugador>` — consulta el saldo de cualquier otro jugador.
- `/bal level [jugador]` — muestra nivel, XP actual/necesaria para el siguiente nivel y tu salario diario actual (el tuyo o el de otro jugador).
- `/baltop [pagina]` — ranking de saldos con el dinero de cada uno, top 10 por página.
- `/pay <jugador> <cantidad>` — transfiere saldo a otro jugador.
- `/subscribe offer <precio>` — te conviertes en vendedor: ofreces un servicio de suscripción a tu propio precio.
- `/subscribe <jugador>` — te suscribes al servicio de ese jugador (te cobra el primer periodo al instante).
- `/subscribe` — muestra tu suscripción activa (si le pagas a alguien) y tu oferta (si vendes algo, con el número de suscriptores).
- `/subscribe offers` — lista todos los servicios de suscripción activos en el servidor y su precio.
- `/subscribe stop` — dejas de ofrecer tu servicio (cancela a todos tus suscriptores).
- `/subscribe cancel` — cancelas tu propia suscripción a otro jugador.
- `/trade <jugador>` — invita a otro jugador a un intercambio seguro.
- `/trade accept` / `/trade deny` — aceptar o rechazar una invitación pendiente.
- `/trade money <monto>` — ofrecer dinero durante un intercambio abierto.
- `/trade cancel` — cancelar el intercambio en curso.

### Administración (requieren OP nivel 2 o consola)
- `/eco give|take|set <jugador> <cantidad>` — modifica saldos manualmente (no otorga XP, es un ajuste administrativo).
- `/eco reload` — recarga todos los archivos de `config/sheyitoscurrency/` sin reiniciar el servidor.
- `/sheyitoscurrency reward <jugador> [monto]` — otorga dinero; ver integración con FTB Quests más abajo.

## Integración con FTB Quests

Sheyito's currency **no depende en tiempo de compilación** de FTB Quests. La integración se hace mediante el comando administrativo `/sheyitoscurrency reward`, pensado para llamarse desde una **Recompensa de tipo "Command"** en el editor de misiones de FTB Quests. Es deliberadamente sencillo: **el mismo comando, sin cambios, va en todas las misiones** — no hay que configurar nada por misión:

```
sheyitoscurrency reward @p
```

Esto paga siempre el importe fijo de `quests_rewards.json` (`amount`, 50 por defecto). Si alguna vez quieres que una misión concreta pague otra cantidad, puedes añadir un monto al final: `sheyitoscurrency reward @p 200`.

Importante sobre cómo cargar esto en FTB Quests:
- En el campo de texto del reward **no** se pone la barra `/` inicial (FTB Quests la añade sola).
- El interruptor **"Run as Player" debe estar DESACTIVADO** (modo consola). En ese modo FTB Quests ejecuta el comando con permiso de operador (nivel 2, lo que este comando requiere) y además resuelve `@p` al jugador que completó la misión.
- `@p` (jugador más cercano) es el selector que hay que usar — **no existe un `@S`** en FTB Quests; si lo usas, el comando falla en silencio porque `@S` no es un selector válido de Minecraft.
- El mod deja un log `Sheyito's currency: /sheyitoscurrency reward invocado para ...` cada vez que el comando se ejecuta — si completas una misión y esa línea no aparece en `logs/latest.log`, el problema está en la configuración del reward de FTB Quests (no en el mod).

Si FTB Quests está presente en el servidor, el log del mod lo detecta automáticamente al arrancar (solo informativo, no cambia el comportamiento).

## Caza de mobs

`config/sheyitoscurrency/mobs.json` define qué entidades (por id de registro, p. ej. `minecraft:zombie`) dan dinero al morir a manos de un jugador. `requireDirectPlayerKill: false` permite que también cuenten las muertes causadas por mascotas domesticadas (lobos, gatos) del jugador.

## Salario diario y niveles

El salario se paga cada `intervalGameDays` **días de juego** (no minutos reales — 1 día de juego son 24000 ticks del mundo, así que si el servidor está apagado el reloj no corre y no hay pagos "atrasados" que recuperar). Por defecto es 1 día de juego.

El importe no es fijo: cada jugador tiene un **nivel** (0 a `maxLevel`, 20 por defecto) que va del salario base (`baseSalary`, 10 $/día) al salario máximo (`maxSalary`, 500 $/día) de forma lineal. Subir de nivel requiere XP, y **cada moneda ganada (no recibida por pago de otro jugador) otorga `xpPerCoin` XP** (0.1 por defecto): matar mobs, cobrar recompensas de misiones, el propio salario, y el dinero que cobras como vendedor de una suscripción, todo suma XP. Las transferencias con `/pay` y los ajustes de `/eco give` **no** dan XP, para que nadie suba de nivel simplemente pasándose dinero entre cuentas.

La XP necesaria para pasar del nivel L-1 al L es `levelCurveBaseXp * fibonacci(L)` — al crecer Fibonacci de forma exponencial, los primeros niveles son rápidos pero los últimos son deliberadamente brutales (con los valores por defecto, llegar al nivel 20 exige del orden de millones de monedas ganadas en total). Todo esto es ajustable en `salary.json` (`baseSalary`, `maxSalary`, `maxLevel`, `xpPerCoin`, `levelCurveBaseXp`). Consulta tu progreso con `/bal level`.

## Suscripciones (100% entre jugadores)

No hay planes predefinidos: cualquier jugador puede vender su propio servicio de suscripción a su propio precio (`/subscribe offer <precio>`), y cualquier otro jugador puede suscribirse (`/subscribe <jugador>`). El precio queda fijado en el momento de suscribirse — si el vendedor lo cambia después, no afecta a quienes ya estaban suscritos. El único ajuste global es cada cuántos días de juego se cobra la renovación (`subscriptions.json`, `intervalGameDays`, 5 por defecto). Si al suscriptor le faltan fondos en el momento del cobro, la suscripción se cancela automáticamente y se le avisa por chat.

## Intercambio seguro (/trade)

GUI estilo cofre vainilla para intercambiar ítems y dinero entre dos jugadores, sin ningún riesgo de estafa ni duplicación:

1. `/trade <jugador>` envía una invitación; el otro jugador tiene que aceptarla explícitamente con `/trade accept` (o el botón clicable en el chat) - a nadie se le abre un GUI sin haberlo pedido.
2. Al aceptar, se abren dos ventanas tipo cofre (una por jugador). Los ítems se arrastran físicamente a la fila superior igual que en cualquier cofre. El dinero se ofrece con `/trade money <monto>` (los menús vainilla no tienen campo de texto).
3. Cada uno ve en tiempo real lo que el otro está ofreciendo (fila espejo de solo lectura) y el dinero ofrecido.
4. Al hacer clic en el ítem de "Confirmar" ambos lados, se llena una barra de 9 paneles de vidrio (verde = confirmado, con sonido en cada paso) durante unos 3 segundos. Mover un ítem o cambiar el monto de dinero mientras tanto reinicia la confirmación.
5. Al completarse la barra, el intercambio se ejecuta de forma atómica: se revalida el saldo de ambos, se transfiere el dinero, y los ítems pasan al inventario del otro jugador (si no cabe completo, se tira al suelo - nunca se pierde).
6. Cerrar la ventana, cancelar, o desconectarse a mitad de un intercambio devuelve automáticamente todo lo que tenías puesto en tu oferta.

Como reutiliza un tipo de menú vainilla (`GENERIC_9x4`, el mismo que un cofre de 4 filas), el cliente no necesita el mod instalado para ver la ventana - solo el servidor sabe que es un intercambio.

## Tiendas de cartel + cofre

Sistema tipo "ChestShop": un cartel sobre o al lado de un cofre vende o compra ítems automáticamente, sin que el dueño tenga que estar conectado.

**Formato del cartel** (4 líneas, sin comandos, solo texto):
```
TuNombreDeUsuario
SELL 10.5
1 minecraft:diamond
(el mod escribe el stock aquí solo)
```
- Línea 1: tu nombre de usuario exacto (así el mod sabe que eres el dueño).
- Línea 2: `SELL <precio>` (la tienda te vende del cofre) o `BUY <precio>` (la tienda te compra hacia el cofre).
- Línea 3: `<cantidad> <item>` — el ítem puede ser solo el nombre (`diamond`) o con namespace completo (`minecraft:diamond`).
- Línea 4: la reescribe el mod automáticamente con el stock actual - no la edites tú.

Reglas:
- Necesitas exactamente un cofre pegado al cartel (arriba, abajo, o a un lado) para que se registre como tienda.
- Un cofre solo puede pertenecer a un jugador, pero puedes poner varios carteles sobre el mismo cofre tuyo (uno para vender, otro para comprar).
- Nadie más que el dueño (u OP nivel 2) puede romper el cartel ni el cofre de una tienda ajena.
- Toda compra/venta se valida antes de tocar nada (stock real del cofre, saldo, espacio en el inventario) - si algo falla, no se mueve absolutamente nada.

## Estructura del código

```
com.sheyito.economicmaster
├── EconomicMaster.java           punto de entrada del mod (MODID = "sheyitoscurrency")
├── config/                       esquemas + carga/recarga de config/sheyitoscurrency/*.json
├── data/                         esquemas de los JSON de datos por-mundo
├── economy/EconomyManager        saldos + XP/nivel: dar/quitar/fijar/pagar/top/giveEarned
├── salary/SalaryManager          salario diario (días de juego) según nivel
├── subscription/SubscriptionManager  ofertas y suscripciones jugador-a-jugador
├── scheduler/                    chequeo cada ~30s de salario/suscripciones + autoguardado
├── events/                       LivingDeathEvent (caza), ciclo de vida del servidor
├── commands/                     /bal /baltop /pay /subscribe /eco /sheyitoscurrency /trade
├── trade/                        TradeSession/TradeMenu/TradeManager - intercambio seguro con GUI
├── shop/                         ShopManager/ShopSignParser/ShopTransactionService - tiendas cartel+cofre
├── integration/FTBQuestsCompat   detección soft-dependency de FTB Quests
└── util/                         JSON, dinero, días de juego (GameTime), curva de niveles (LevelCurve)
```

Nota: el paquete Java (`com.sheyito.economicmaster`) y el nombre de la clase principal (`EconomicMaster.java`) se mantienen sin cambios — son estructura interna invisible para el jugador. Lo que sí cambió es el `mod_id` (`sheyitoscurrency`), que es lo que determina el nombre del jar, la carpeta de configuración, la carpeta de datos por mundo, y el comando de FTB Quests.
