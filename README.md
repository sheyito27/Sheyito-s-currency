# Sheyito's currency

Mod **100% server-side** para NeoForge 1.21.1: economía virtual (moneda "Sheyicoins") basada en comandos, `/baltop`, suscripciones jugador-a-jugador, salario diario con sistema de niveles, caza de mobs con whitelist configurable (desactivada por defecto), intercambio seguro `/trade` con GUI tipo cofre donde el dinero se deposita como ítems, tiendas de cartel+cofre, deuda por muerte, e integración **automática** con **FTB Quests** (toda misión completada paga sola, sin configurar nada por misión).

No registra bloques, ítems, pantallas ni nada renderizado en cliente: los clientes pueden conectarse al servidor sin instalar el mod.

## Documentación técnica

Cada feature tiene su propia ficha explicando su diseño en lenguaje natural (qué es, por qué se
hizo así, cómo funciona) dentro de [`docs/features/`](docs/features/):
[saldo y ranking](docs/features/saldoYRanking.md),
[salario diario](docs/features/salarioDiario.md),
[`/pay`](docs/features/pagosP2P.md),
[`/trade`](docs/features/tradeSeguro.md),
[suscripciones](docs/features/suscripcionesP2P.md),
[tiendas](docs/features/tiendasAutomaticas.md),
[caza de mobs](docs/features/cazaDeMobs.md),
[integración FTB Quests](docs/features/integracionFtbQuests.md),
[compra de XP](docs/features/compraXP.md),
[deuda por muerte](docs/features/deudaPorMuerte.md).

Varias features comparten los mismos patrones estructurales; cada uno está documentado una sola
vez en su propia ficha en vez de repetido en cada feature que lo usa:
[config autogenerada](docs/features/patronConfig.md),
[comandos con Brigadier](docs/features/patronComandos.md),
[manager con ciclo de vida](docs/features/patronManager.md),
[invitación pendiente](docs/features/patronInvitacionPendiente.md),
[validar luego mutar](docs/features/patronValidarLuegoMutar.md).

El roadmap de features pendientes vive en [`docs/proposals.md`](docs/proposals.md).

## Requisitos de compilación

- JDK 21
- Conexión a internet la primera vez (Gradle descarga NeoForge/NeoGradle y las dependencias)

```bash
./gradlew build
```

El `.jar` resultante queda en `build/libs/sheyitoscurrency-1.0.0.jar`. Cópialo a la carpeta `mods/` del servidor (NeoForge 1.21.1, `neo_version` recomendado `21.1.248` o superior de la rama `21.1.x`). También funciona igual en un mundo de un solo jugador: es un servidor integrado, así que basta con poner el jar en los `mods/` del cliente.

## Persistencia

- **Configuración** (editable, se autogenera en el primer arranque): `config/sheyitoscurrency/`
  - `general.json` — nombre de la moneda (`currencyName`, "Sheyicoins" por defecto), decimales, saldo inicial, opciones de aviso por chat.
  - `mobs.json` — whitelist de entidades y el dinero que dan al morir a manos de un jugador. **Desactivado por defecto** (`enabled: false`).
  - `salary.json` — salario diario y la curva de niveles (ver más abajo).
  - `quests_rewards.json` — un único importe fijo (`amount`, 10 por defecto) que paga toda misión de FTB Quests, automáticamente.
  - `subscriptions.json` — solo el intervalo de cobro (`intervalGameDays`, 5 días de juego por defecto). Las suscripciones en sí son 100% entre jugadores, no hay nada más que configurar aquí.
  - `shop.json` — tiempo límite en ticks para terminar de escribir un cartel de tienda (`pendingSignTimeoutTicks`, 600 = 30s por defecto).
  - `xp_shop.json` — precio en Sheyicoins por punto de experiencia vanilla (`coinsPerXpPoint`, 1.0 por defecto).
  - `debt.json` — penalización por muerte y plazo de la deuda (ver más abajo).
- **Datos de jugadores** (saldos, XP/nivel, ofertas y suscripciones activas, últimos pagos, tiendas registradas): dentro de la carpeta del mundo, en `<mundo>/sheyitoscurrency/`. Viaja con la copia de seguridad del mundo.

## Comandos

### Jugadores (sin OP, todos públicos)
- `/bal` — muestra tu saldo.
- `/bal player <jugador>` — consulta el saldo de cualquier otro jugador.
- `/bal level [jugador]` — muestra nivel, XP actual/necesaria para el siguiente nivel y tu salario diario actual (el tuyo o el de otro jugador).
- `/baltop [pagina]` — ranking de saldos con el dinero de cada uno, top 10 por página.
- `/pay <jugador> <cantidad>` — transfiere saldo a otro jugador.
- `/buy xp <cantidad>` — compra puntos de experiencia vanilla de Minecraft con Sheyicoins (no tiene relación con el nivel de salario).
- `/subscribe offer <precio>` — te conviertes en vendedor: ofreces un servicio de suscripción a tu propio precio.
- `/subscribe <jugador>` — te suscribes al servicio de ese jugador (te cobra el primer periodo al instante).
- `/subscribe` — muestra tu suscripción activa (si le pagas a alguien) y tu oferta (si vendes algo, con el número de suscriptores).
- `/subscribe offers` — lista todos los servicios de suscripción activos en el servidor y su precio.
- `/subscribe stop` — dejas de ofrecer tu servicio (cancela a todos tus suscriptores).
- `/subscribe cancel` — cancelas tu propia suscripción a otro jugador.
- `/trade <jugador>` — invita a otro jugador a un intercambio seguro.
- `/trade accept` / `/trade deny` — aceptar o rechazar una invitación pendiente.
- `/trade cancel` — cancelar el intercambio en curso. El dinero se ofrece depositando ítems directamente en el GUI (ver más abajo), no con un comando.
- `/debt` — muestra tu deuda actual (importe, plazo, si está vencida).
- `/debt player <jugador>` — consulta la deuda de cualquier otro jugador.

### Administración (requieren OP nivel 2 o consola)
- `/eco give|take|set <jugador> <cantidad>` — modifica saldos manualmente (no otorga XP, es un ajuste administrativo).
- `/eco charge <jugador> <cantidad>` — resta saldo sin comprobar fondos, puede dejarlo en negativo (deuda). Pensado para probar/forzar el mecanismo de deuda por muerte manualmente.
- `/eco reload` — recarga todos los archivos de `config/sheyitoscurrency/` sin reiniciar el servidor.
- `/sheyitoscurrency reward <jugador> [monto]` — otorga dinero; ver integración con FTB Quests más abajo.

## Integración con FTB Quests

**Totalmente automática, sin configurar nada por misión.** Si FTB Quests está presente en el servidor, el mod se engancha directamente a su evento interno de misión completada (`ObjectCompletedEvent.QUEST`): **toda misión terminada, de cualquier tipo, paga automáticamente** el importe de `quests_rewards.json` (`amount`, 10 por defecto) a todos los miembros online del equipo que la completó. No hace falta tocar el editor de misiones ni añadir ninguna recompensa manualmente.

Sheyito's currency **no depende en tiempo de compilación** de FTB Quests de forma dura: se compila contra sus clases con `compileOnly` (nunca se empaqueta ni se exige), y todo el código que las referencia vive aislado en una sola clase que solo se toca si `ModList` detecta `ftbquests` cargado al arrancar — si no está instalado, el mod funciona exactamente igual sin él.

Como alternativa/complemento manual sigue disponible el comando administrativo `/sheyitoscurrency reward <jugador> [monto]`, pensado para llamarse desde una **Recompensa de tipo "Command"** en una misión puntual si querés que pague un importe distinto al automático:

```
sheyitoscurrency reward @p 200
```

- En el campo de texto del reward **no** se pone la barra `/` inicial (FTB Quests la añade sola).
- El interruptor **"Run as Player" debe estar DESACTIVADO** (modo consola), así FTB Quests resuelve `@p` al jugador que completó la misión con permiso de operador.
- `@p` (jugador más cercano) es el selector correcto — **no existe un `@S`** en FTB Quests.

## Caza de mobs

**Desactivado por defecto** (`enabled: false` en `mobs.json`) — actívalo si querés que matar mobs también dé dinero. `config/sheyitoscurrency/mobs.json` define qué entidades (por id de registro, p. ej. `minecraft:zombie`) dan dinero al morir a manos de un jugador. `requireDirectPlayerKill: false` permite que también cuenten las muertes causadas por mascotas domesticadas (lobos, gatos) del jugador.

## Deuda por muerte

Morir tiene un coste económico, con dos ramas según tu saldo en ese momento (`debt.json`,
`balanceThreshold`, 500 SC por defecto):

- **Saldo ≤ umbral:** penalización fija (`penaltyAmount`, 500 SC por defecto) que puede dejarte
  en negativo — eso es la deuda, con un plazo estricto (`deadlineGameDays`, 1 día de juego por
  defecto) para volver a saldo ≥ 0.
- **Saldo > umbral:** se te cobra un porcentaje de tu patrimonio (`penaltyPercent`, 30% por
  defecto) — nunca te deja en negativo.

Consulta tu deuda con `/debt` (o `/debt player <jugador>` la de otro). Qué pasa si el plazo vence
sin pagar está fuera de esta feature (es la futura feature de embargo del roadmap).

## Salario diario y niveles

El salario se paga cada `intervalGameDays` **días de juego** (no minutos reales — 1 día de juego son 24000 ticks del mundo, así que si el servidor está apagado el reloj no corre y no hay pagos "atrasados" que recuperar). Por defecto es 1 día de juego.

El importe no es fijo: cada jugador tiene un **nivel** (0 a `maxLevel`, 50 por defecto) que va del salario base (`baseSalary`, 10 $/día) al salario máximo (`maxSalary`, 100 $/día) de forma lineal. Subir de nivel requiere XP, y **cada moneda ganada (no recibida por pago de otro jugador) otorga `xpPerCoin` XP** (0.1 por defecto): matar mobs, cobrar recompensas de misiones, el propio salario, y el dinero que cobras como vendedor de una suscripción, todo suma XP. Las transferencias con `/pay` y los ajustes de `/eco give` **no** dan XP, para que nadie suba de nivel simplemente pasándose dinero entre cuentas.

La XP necesaria para pasar del nivel L-1 al L es `levelCurveBaseXp * fibonacci(L)` — al crecer Fibonacci de forma exponencial, los primeros niveles son rápidos pero los últimos son deliberadamente brutales (con los valores por defecto, llegar al nivel 50 exige sumas de monedas ganadas absolutamente descomunales). Todo esto es ajustable en `salary.json` (`baseSalary`, `maxSalary`, `maxLevel`, `xpPerCoin`, `levelCurveBaseXp`). Consulta tu progreso con `/bal level`.

## Suscripciones (100% entre jugadores)

No hay planes predefinidos: cualquier jugador puede vender su propio servicio de suscripción a su propio precio (`/subscribe offer <precio>`), y cualquier otro jugador puede suscribirse (`/subscribe <jugador>`). El precio queda fijado en el momento de suscribirse — si el vendedor lo cambia después, no afecta a quienes ya estaban suscritos. El único ajuste global es cada cuántos días de juego se cobra la renovación (`subscriptions.json`, `intervalGameDays`, 5 por defecto). Si al suscriptor le faltan fondos en el momento del cobro, la suscripción se cancela automáticamente y se le avisa por chat.

## Intercambio seguro (/trade)

GUI estilo cofre vainilla (5 filas) para intercambiar ítems y dinero entre dos jugadores, sin ningún riesgo de estafa ni duplicación:

1. `/trade <jugador>` envía una invitación; el otro jugador tiene que aceptarla explícitamente con `/trade accept` (o el botón clicable en el chat) - a nadie se le abre un GUI sin haberlo pedido.
2. Al aceptar, se abren dos ventanas tipo cofre (una por jugador):
   - **Fila 1**: tus ítems ofrecidos (arrastralos igual que en cualquier cofre).
   - **Fila 2**: lo que te ofrece el otro jugador (solo lectura, en vivo).
   - **Fila 3**: la barra de progreso de confirmación.
   - **Fila 4**: tus slots de dinero (fondo verde) — depositá lingotes/gemas para ofrecer dinero: lingote de cobre = 1, lingote de hierro = 10, lingote de oro = 100, diamante = 1000, lingote de netherita = 10.000. El total se recalcula solo. Para "quitar" dinero, simplemente sacá el ítem del slot (como en cualquier cofre) - no hay un comando ni un botón separado para eso. Al lado: tu total ofrecido, el botón de confirmar, y el de cancelar.
   - **Fila 5**: espejo de solo lectura de los depósitos de dinero del otro jugador, y su total.
3. Al hacer clic en "Confirmar" ambos lados, se llena una barra de 9 paneles de vidrio (verde = confirmado, con sonido en cada paso) durante unos 3 segundos. Mover un ítem, depositar/retirar dinero, o cambiar cualquier cosa mientras tanto reinicia la confirmación.
4. Al completarse la barra, el intercambio se ejecuta de forma atómica: los lingotes/gemas depositados se convierten en dinero real para el otro jugador, y los ítems ofrecidos pasan al inventario del otro (si no cabe completo, se tira al suelo - nunca se pierde). Sonido de éxito (recogida de experiencia).
5. Cerrar la ventana, cancelar, o desconectarse a mitad de un intercambio devuelve automáticamente todo lo que tenías puesto en tu oferta, ítems y dinero incluidos (como ítems, no como saldo). Sonido de fallo (yunque).

Como reutiliza un tipo de menú vainilla (`GENERIC_9x5`, el mismo que un cofre grande de 5 filas), el cliente no necesita el mod instalado para ver la ventana - solo el servidor sabe que es un intercambio.

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
├── economy/EconomyManager        saldos + XP/nivel: dar/quitar/fijar/pagar/top/giveEarned/charge (sobregiro)
├── salary/SalaryManager          salario diario (días de juego) según nivel
├── subscription/SubscriptionManager  ofertas y suscripciones jugador-a-jugador
├── debt/DebtManager              plazo de la deuda por muerte (días de juego)
├── scheduler/                    chequeo cada ~30s de salario/suscripciones + autoguardado
├── events/                       LivingDeathEvent (caza, deuda por muerte), ciclo de vida del servidor
├── commands/                     /bal /baltop /pay /subscribe /eco /sheyitoscurrency /trade /debt
├── trade/                        TradeSession/TradeMenu/TradeManager - intercambio seguro con GUI
├── shop/                         ShopManager/ShopSignParser/ShopTransactionService - tiendas cartel+cofre
├── integration/                  FTBQuestsCompat (deteccion) + FtbQuestsIntegration (recompensa automatica, compileOnly)
└── util/                         JSON, dinero, sonidos de transaccion, días de juego (GameTime), curva de niveles (LevelCurve)
```

Nota: el paquete Java (`com.sheyito.economicmaster`) y el nombre de la clase principal (`EconomicMaster.java`) se mantienen sin cambios — son estructura interna invisible para el jugador. Lo que sí cambió es el `mod_id` (`sheyitoscurrency`), que es lo que determina el nombre del jar, la carpeta de configuración, la carpeta de datos por mundo, y el comando de FTB Quests.
