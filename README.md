# Sheyito's currency

Mod **100% server-side** para NeoForge 1.21.1: economía virtual (moneda "Sheyicoins") basada en comandos, `/baltop`, suscripciones jugador-a-jugador, salario diario con sistema de niveles, caza de mobs con whitelist configurable (desactivada por defecto), intercambio seguro `/trade` con GUI tipo cofre donde el dinero se deposita como ítems, tiendas de cartel+cofre, penalización por muerte, peaje de movilidad opcional con **Waystones**, desbloqueo de pago por dimensión (Nether, End y cualquier dimensión modded), cobro por reclamar chunks con **FTB Chunks**, IVA de transmisión que quema parte de cada pago/trade/compra/suscripción, embargo de equipo si te quedas en deuda, renta progresiva semanal sobre ganancias y renta de force-load de chunks, e integración **automática** con **FTB Quests** (toda misión completada paga sola, sin configurar nada por misión).

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
[penalización por muerte](docs/features/penalizacionPorMuerte.md),
[peaje de movilidad (Waystones)](docs/features/peajeMovilidadWaystones.md),
[desbloqueo de dimensiones](docs/features/desbloqueoDimensiones.md),
[renta de chunks (FTB Chunks)](docs/features/rentaDeChunks.md),
[IVA de transmisión](docs/features/ivaDeTransmision.md),
[embargo silencioso y brutal](docs/features/embargoDeudas.md),
[renta progresiva sobre ganancias](docs/features/rentaProgresiva.md).

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
  - `debt.json` — porcentaje de la penalización por muerte (ver más abajo).
  - `waystone_toll.json` — coste en Sheyicoins de usar un waystone del mod Waystones, 100 por defecto (ver más abajo).
  - `dimension_unlock.json` — coste en Sheyicoins de desbloquear una dimensión (Nether, End, o cualquier otra), 5000 por defecto (ver más abajo).
  - `chunk_claim.json` — solo `enabled`; el coste de reclamar un chunk con FTB Chunks escala como `n^1.5` por jugador y no es configurable (ver más abajo).
  - `transmission_tax.json` — porcentaje de IVA que se quema en `/pay`, el dinero de `/trade`, las tiendas de cartel y las suscripciones (`taxPercent`, 10% por defecto, ver más abajo).
  - `embargo.json` — plazo de gracia en segundos (`graceSeconds`, 30 por defecto), condiciones de cierre de la votación de qué se incauta (`minVotersToClose`, `minVoteGameDays`), duración/incrementos de puja de la subasta de la pool (`auctionDurationGameDays`, `bidIncrements`) y el bloque de columnas/techo del "puesto de subastas" (`auctionStandBlockId`, ver más abajo).
  - `rent.json` — cadencia compartida (`intervalGameDays`, 7 por defecto) de la renta progresiva sobre ganancias (`profitBrackets`) y la renta de force-load de chunks (`forceLoadRentBase`, ver más abajo).
- **Datos de jugadores** (saldos, XP/nivel, ofertas y suscripciones activas, últimos pagos, tiendas registradas, dimensiones desbloqueadas, chunks reclamados/force-loaded, seguimiento de renta): dentro de la carpeta del mundo, en `<mundo>/sheyitoscurrency/`. Viaja con la copia de seguridad del mundo.

## Comandos

### Jugadores (sin OP, todos públicos)
- `/bal` — muestra tu saldo.
- `/bal player <jugador>` — consulta el saldo de cualquier otro jugador.
- `/bal level [jugador]` — muestra nivel, XP actual/necesaria para el siguiente nivel y tu salario diario actual (el tuyo o el de otro jugador).
- `/baltop [pagina]` — ranking de saldos con el dinero de cada uno, top 10 por página.
- `/pay <jugador> <cantidad>` — transfiere saldo a otro jugador. Quema IVA de transmisión (ver más abajo): el emisor paga de más, el receptor recibe de menos.
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
- `/liquidation vote` — si hay una votación de embargo activa en la que puedes participar (nunca si eres la víctima), abre el menú para votar qué objeto incautado se subasta (ver más abajo).
- `/liquidation auction` — si hay una subasta activa en la que puedes participar (nunca si eres la víctima del objeto en juego), abre el menú para pujar (ver más abajo). Se puja clicando botones, no escribiendo cifras.

### Administración (requieren OP nivel 2 o consola)
- `/eco give|take|set <jugador> <cantidad>` — modifica saldos manualmente (no otorga XP, es un ajuste administrativo).
- `/eco charge <jugador> <cantidad>` — resta saldo sin comprobar fondos, puede dejarlo en negativo. No hay un estado de "deuda" separado: un saldo negativo se consulta con `/bal`, igual que uno positivo.
- `/eco reload` — recarga todos los archivos de `config/sheyitoscurrency/` sin reiniciar el servidor.
- `/sc reward <jugador> [monto]` — otorga dinero; ver integración con FTB Quests más abajo.
- `/sc dimension lock <jugador> <dimension>` — revierte el desbloqueo de una dimensión para ese jugador (sin reembolsar), para poder reprobar el flujo de pago sin reiniciar el mundo.
- `/sc chunk reset <jugador>` — pone a 0 el recuento de chunks reclamados de ese jugador (sin reembolsar), para poder reprobar la curva de precio sin desreclamar chunk a chunk.
- `/sc liquidation withdraw` — saca el ítem más antiguo de la pool de subastas y lo entrega al admin que lo ejecuta, reembolsando primero cualquier puja activa sobre él (ver más abajo). Es la única forma de sacar un ítem de la pool al margen de la subasta.
- `/sc liquidation close <player>` — fuerza el cierre de la votación de embargo más antigua de ese jugador ya mismo, saltándose tanto el mínimo de votantes como los días de juego necesarios (que se miden en tiempo real de servidor acumulado, no en fecha - una sesión de pruebas corta puede no acumular suficiente aunque todo el mundo ya haya votado).
- `/sc rent force <player>` — fuerza un cobro inmediato de la renta progresiva sobre ganancias y de la renta de force-load de chunks de ese jugador, ignorando si ya pasaron los días de intervalo de verdad (ver más abajo).

Todos los comandos de administración/pruebas viven bajo la raíz compartida `/sc` (Brigadier fusiona los subcomandos de cada clase en un único árbol).

## Integración con FTB Quests

**Totalmente automática, sin configurar nada por misión.** Si FTB Quests está presente en el servidor, el mod se engancha directamente a su evento interno de misión completada (`ObjectCompletedEvent.QUEST`): **toda misión terminada, de cualquier tipo, paga automáticamente** el importe de `quests_rewards.json` (`amount`, 10 por defecto) a todos los miembros online del equipo que la completó. No hace falta tocar el editor de misiones ni añadir ninguna recompensa manualmente.

Sheyito's currency **no depende en tiempo de compilación** de FTB Quests de forma dura: se compila contra sus clases con `compileOnly` (nunca se empaqueta ni se exige), y todo el código que las referencia vive aislado en una sola clase que solo se toca si `ModList` detecta `ftbquests` cargado al arrancar — si no está instalado, el mod funciona exactamente igual sin él.

Como alternativa/complemento manual sigue disponible el comando administrativo `/sc reward <jugador> [monto]`, pensado para llamarse desde una **Recompensa de tipo "Command"** en una misión puntual si quieres que pague un importe distinto al automático:

```
sc reward @p 200
```

- En el campo de texto del reward **no** se pone la barra `/` inicial (FTB Quests la añade sola).
- El interruptor **"Run as Player" debe estar DESACTIVADO** (modo consola), así FTB Quests resuelve `@p` al jugador que completó la misión con permiso de operador.
- `@p` (jugador más cercano) es el selector correcto — **no existe un `@S`** en FTB Quests.

## Caza de mobs

**Desactivado por defecto** (`enabled: false` en `mobs.json`) — actívalo si quieres que matar mobs también dé dinero. `config/sheyitoscurrency/mobs.json` define qué entidades (por id de registro, p. ej. `minecraft:zombie`) dan dinero al morir a manos de un jugador. `requireDirectPlayerKill: false` permite que también cuenten las muertes causadas por mascotas domesticadas (lobos, gatos) del jugador.

## Muerte

Morir siempre tiene un coste económico: pierdes un porcentaje de tu saldo actual (`debt.json`,
`penaltyPercent`, 50% por defecto). Al ser un porcentaje de lo que tienes en ese momento, nunca
puede dejarte en negativo ni romper la banca.

## Peaje de movilidad (Waystones)

**Integración opcional, sin dependencia dura** — si el mod [Waystones](https://modrinth.com/mod/waystones)
está instalado, usar un waystone cobra `cost` Sheyicoins (`waystone_toll.json`, 100 por defecto).
Si no está instalado, el mod funciona igual, solo que sin esta feature.

Si no te alcanza el saldo, **se bloquea el teletransporte** — no se cobra nada y Waystones muestra
un aviso. A diferencia de `/eco charge` (herramienta de admin), este peaje nunca deja el saldo en
negativo; ese mecanismo queda reservado para una futura feature de pagos obligatorios.

## Desbloqueo de dimensiones

Viajar a cualquier dimensión que no sea el Overworld (Nether, End, o cualquier dimensión modded —
se detectan todas automáticamente, nada hardcodeado) cuesta `price` Sheyicoins la primera vez
(`dimension_unlock.json`, 5000 por defecto). Si no te alcanza, **el portal no te deja pasar** y te
quedas en el Overworld. Si pagas, esa dimensión queda desbloqueada para siempre para ti — nunca
más se te vuelve a cobrar por entrar a ella. El mensaje siempre dice qué dimensión es, resaltada
en morado. Un admin puede revertir el desbloqueo de un jugador con `/sc dimension lock` (ver
comandos más abajo) para volver a probar el flujo sin reiniciar el mundo.

## Renta de chunks (FTB Chunks)

**Integración opcional, sin dependencia dura** — si el mod [FTB Chunks](https://www.curseforge.com/minecraft/mc-mods/ftb-chunks-forge)
está instalado, reclamar un chunk cobra Sheyicoins. **El precio no es fijo ni configurable**: sube
como `n^1.5` con cada chunk que ya tengas — el chunk número `n` (1º, 2º, 3º...) cuesta `1000 * n^1.5`
(1.000 / ~2.828 / ~5.196 / ... / ~31.623 en el 10º), para desincentivar acaparar territorio sin
volverse inalcanzable. Si no está instalado, el mod funciona igual, solo que sin esta feature.

Este mod no implementa protección ni reclamo de chunks — eso lo hace FTB Chunks enteramente. Si no
te alcanza el saldo para el siguiente chunk, **el reclamo se bloquea** y FTB Chunks muestra el
motivo. El reclamo sigue siendo pago único; lo único con renta periódica es el **force-load**: cada
`intervalGameDays` días (`rent.json`, 7 por defecto) se cobra `forceLoadRentBase * n^1.5` (base 10,
`n` = chunks que tenés force-loaded ahora mismo) por mantenerlos cargados, también estando
desconectado. Si no cubrís el total, se descargan **todos** de golpe — inmediato si estás online,
en cuanto te reconectes si no.

## IVA de transmisión

Toda transacción de compraventa entre jugadores — `/pay`, el dinero de `/trade`, comprar o vender en
una tienda de cartel, y cada cobro de una suscripción — quema un porcentaje configurable
(`taxPercent`, 10% por defecto, `transmission_tax.json`) **en ambos lados a la vez**: el pagador paga
de más, el receptor recibe de menos. Con el 10% por defecto, una transacción de 100 SC hace que el
pagador pague 110 y el receptor reciba 90 — un 20% del valor nominal se quema en total. El importe
pactado (lo que escribís en `/pay` o el precio del cartel) nunca cambia; el IVA se calcula en el
momento del cobro. No afecta a `/eco`, `/sc reward`, el salario, la caza de mobs, ni a los peajes de
waystones/dimensiones/chunks — ninguno de esos es una compraventa entre jugadores.

## Embargo silencioso y brutal

Si tu saldo se vuelve negativo (hoy solo posible vía `/eco charge`, herramienta de admin — la
futura feature de "pagos obligatorios" será la vía real de gameplay), tienes `graceSeconds`
segundos **reales** (30 por defecto, pausados mientras estás desconectado) para saldarlo vendiendo
en tiendas, cobrando salario o completando misiones, con avisos por chat en cuenta atrás (tiempo
completo al entrar, cada 10 segundos, aviso dedicado a los 10 segundos, y del 5 al 1 uno por
segundo). Mientras tanto no puedes recibir dinero de otros jugadores (`/pay`/`/trade`), tirar
objetos al suelo, ni abrir **ningún** contenedor - cofre, ender chest, shulker, barril, uno de otro
mod, o incluso la ventana de `/trade` - para que no puedas esconder tu equipo.

Si se agota el plazo, se ejecuta todo de golpe: se te incauta del inventario (equipado y suelto, sin
distinción entre ambos) toda armadura, arma o herramienta, tu saldo vuelve a exactamente 0, y no hay
marcha atrás — pagar después no recupera nada. En cuanto haya suficientes jugadores conectados (sin
contar a la víctima), se abre una votación secreta (`/liquidation vote`, menú tipo cofre) sobre cuál
de los objetos incautados se manda a la pool de subastas del servidor; el resto se te devuelve en
cuanto cierra - si era algo que llevabas equipado y esa ranura sigue libre, vuelve puesto, no como
ítem suelto. La votación se cierra solo cuando hay suficientes votos **y** han pasado suficientes
días de juego a la vez (`minVotersToClose`, `minVoteGameDays`).

El objeto que gana la votación entra en la pool, pero **no se subasta solo**: la única forma de
elegir qué sale a puja es construir un **puesto de subastas** - un atril con 3 columnas y un techo
(bloque configurable, `auctionStandBlockId`) formando un hueco de 2 de alto. Al completarlo aparece
un aldeano fijo dentro (con partículas y sonido); hablar con él abre un menú con todo lo que hay en
la pool, y clicar un objeto lo pone en juego. Solo puede haber una subasta activa a la vez.

Esa subasta sí es **con pujas de verdad** (`/liquidation auction`, menú tipo cofre con botones -
nada de escribir cifras por chat): un botón por cada incremento configurado (`bidIncrements`) puja
`pujaActual + incremento`, más un botón para pujar tu saldo máximo. Pujar retiene el dinero al
instante; si te superan, se te devuelve íntegro. La puja se cierra pasados
`auctionDurationGameDays` días de juego - si ganó alguien, se lleva el ítem y su dinero se queda
quemado (nunca se redistribuye, ni siquiera a la víctima original); si nadie pujó, el ítem vuelve a
esperar en la pool hasta que alguien lo elija otra vez en el puesto - nada se reabre solo.
`/sc liquidation withdraw` sigue existiendo como válvula de escape de admin (saca el
ítem de la cabeza de la cola pase lo que pase, reembolsando primero cualquier puja activa sobre él).

## Renta progresiva sobre ganancias

Cada `intervalGameDays` días de juego (7 por defecto, `rent.json`), se cobra un porcentaje sobre lo
que **ganaste** en ese periodo — no tu patrimonio total, y no un balance neto — según el tramo:
1-10K → 10%, 10K-100K → 20%, 100K-1M → 30%, 1M en adelante → 40% (tope). El tipo es **plano por
tramo**, no marginal: toda la ganancia se grava al porcentaje de su tramo final.

**Es ganancia bruta, no neta**: cada ingreso (venta, `/pay` recibido, salario, recompensas...) se
va sumando a una cuenta aparte a medida que ocurre; lo que gastes o pierdas mientras tanto nunca se
resta de esa cuenta. Si ganás 10.000 en la semana pero por separado perdés 20.000, igual se te
cobra el 10% de los 10.000 ganados (1.000) — perder saldo es gasto, ajeno a esta renta, nunca un
"crédito" contra una ganancia.

**A diferencia de cualquier otro cobro del mod, esta renta sí puede dejarte en saldo negativo**: es
la única feature (aparte de `/eco charge`, de admin) que no bloquea el cobro si no te alcanza. Es
la primera vía de gameplay real capaz de disparar el plazo de gracia del
[embargo por deuda](#embargo-silencioso-y-brutal).

## Salario diario y niveles

El salario se paga cada `intervalGameDays` **días de juego** (no minutos reales — 1 día de juego son 24000 ticks del mundo, así que si el servidor está apagado el reloj no corre y no hay pagos "atrasados" que recuperar). Por defecto es 1 día de juego.

El importe no es fijo: cada jugador tiene un **nivel** (0 a `maxLevel`, 50 por defecto) que va del salario base (`baseSalary`, 10 $/día) al salario máximo (`maxSalary`, 100 $/día) de forma lineal. Subir de nivel requiere XP, y **cada moneda ganada (no recibida por pago de otro jugador) otorga `xpPerCoin` XP** (0.1 por defecto): matar mobs, cobrar recompensas de misiones, el propio salario, y el dinero que cobras como vendedor de una suscripción, todo suma XP. Las transferencias con `/pay` y los ajustes de `/eco give` **no** dan XP, para que nadie suba de nivel simplemente pasándose dinero entre cuentas.

La XP necesaria para pasar del nivel L-1 al L es `levelCurveBaseXp * fibonacci(L)` — al crecer Fibonacci de forma exponencial, los primeros niveles son rápidos pero los últimos son deliberadamente brutales (con los valores por defecto, llegar al nivel 50 exige sumas de monedas ganadas absolutamente descomunales). Todo esto es ajustable en `salary.json` (`baseSalary`, `maxSalary`, `maxLevel`, `xpPerCoin`, `levelCurveBaseXp`). Consulta tu progreso con `/bal level`.

## Suscripciones (100% entre jugadores)

No hay planes predefinidos: cualquier jugador puede vender su propio servicio de suscripción a su propio precio (`/subscribe offer <precio>`), y cualquier otro jugador puede suscribirse (`/subscribe <jugador>`). El precio queda fijado en el momento de suscribirse — si el vendedor lo cambia después, no afecta a quienes ya estaban suscritos. El único ajuste global es cada cuántos días de juego se cobra la renovación (`subscriptions.json`, `intervalGameDays`, 5 por defecto). Si al suscriptor le faltan fondos en el momento del cobro (precio pactado + IVA de transmisión, ver más abajo), la suscripción se cancela automáticamente y se le avisa por chat.

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

Sistema tipo "ChestShop": un cartel sobre o al lado de un cofre vende o compra ítems automáticamente, sin que el dueño tenga que estar conectado. Cada compra/venta quema el IVA de transmisión (ver más abajo): quien paga paga de más, quien recibe recibe de menos.

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
├── dimension/DimensionUnlockManager  dimensiones que cada jugador ya pago (Nether, End, modded)
├── chunk/ChunkClaimRegistry      chunks reclamados (precio n^1.5) y force-loaded (renta n^1.5 base 10) por jugador
├── rent/                         RentManager/RentLogic - renta progresiva semanal sobre ganancias (tipo plano por tramo)
├── embargo/                      EmbargoManager/EmbargoScheduler/EmbargoSeizureLogic/EmbargoBlockListener/EmbargoVoteMenu/LiquidationAuctionMenu/AuctionStandListener/AuctionStandSelectionMenu - plazo de gracia, incautación, votación y el puesto de subastas fisico
├── auction/AuctionPoolManager    pool con subasta de pujas sobre el item elegido en el puesto, /sc liquidation withdraw como valvula de escape de admin
├── scheduler/                    chequeo cada ~30s de salario/suscripciones/cierre de votaciones/rentas + autoguardado
├── events/                       LivingDeathEvent (caza, penalización por muerte), EntityTravelToDimensionEvent (desbloqueo), ciclo de vida del servidor
├── commands/                     /bal /baltop /pay /subscribe /eco /trade /liquidation + /sc (reward, dimension lock, chunk reset, liquidation withdraw/close, rent force - admin/dev)
├── trade/                        TradeSession/TradeMenu/TradeManager - intercambio seguro con GUI
├── shop/                         ShopManager/ShopSignParser/ShopTransactionService - tiendas cartel+cofre
├── integration/                  FTBQuestsCompat (recompensa) + WaystonesCompat (peaje) + FTBChunksCompat (reclamo/force-load de chunk) - todas compileOnly
└── util/                         JSON, dinero, sonidos de transaccion, días de juego (GameTime), curva de niveles (LevelCurve), ItemStackJson (persistir ItemStack real)
```

Nota: el paquete Java (`com.sheyito.economicmaster`) y el nombre de la clase principal (`EconomicMaster.java`) se mantienen sin cambios — son estructura interna invisible para el jugador. Lo que sí cambió es el `mod_id` (`sheyitoscurrency`), que es lo que determina el nombre del jar, la carpeta de configuración y la carpeta de datos por mundo — no el nombre de los comandos, que viven todos bajo la raíz corta `/sc` (ver más arriba).
