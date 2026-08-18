# Embargo silencioso y brutal

**Estado:** implementado.
**Código relacionado:** `LiquidationConfig.java`, `LiquidationManager.java`, `LiquidationData.java`, `AuctionVote.java`, `LiquidationScheduler.java`, `LiquidationSeizureLogic.java`, `LiquidationBlockListener.java`, `LiquidationVoteMenu.java`, `LiquidationCommand.java`, `AuctionPoolManager.java`, `AuctionPoolData.java`, `LiquidationAuctionMenu.java`, `AuctionStandListener.java`, `AuctionStandSelectionMenu.java`, `ItemStackJson.java`.
**Patrones:** [manager](patronManager.md), [config](patronConfig.md), [comandos](patronComandos.md).

## Qué es esto

Cuando el saldo de un jugador se vuelve negativo, empieza a correr un plazo de gracia real (30
segundos por defecto, `graceSeconds`) para saldarlo, con avisos por chat en cuenta atrás para que
se note de verdad. Si no lo hace a tiempo, el mod le **incauta** del inventario todo lo que sea
armadura, arma o herramienta (equipada y suelta, sin distinción entre ambas) le devuelve el saldo a
exactamente 0, y guarda lo incautado hasta que la comunidad vota **una sola pieza** para mandar a
una pool de subastas — el resto se le devuelve. No hay reembolso ni marcha atrás: pagar después de
que se ejecute el embargo no recupera nada.

**Historia:** cuando se construyó esta feature, la única vía real para caer en saldo negativo era
el admin-only `/eco charge` — el enganche a `EconomyManager.setBalance()` se hizo genérico a
propósito para no depender de eso. Esa previsión ya dio sus frutos: la
[renta progresiva sobre ganancias](rentaProgresiva.md) es ahora la primera vía de **gameplay real**
(no de admin) que puede dejar el saldo en negativo y disparar este plazo de gracia, sin haber
tenido que tocar una sola línea de este archivo.

## Cómo funciona

### El plazo de gracia (30s reales, pausado si estás offline)

`EconomyManager.setBalance()` es el único método por el que pasan `give`/`take`/`charge` — ahí, si
el saldo cruza de ≥0 a negativo, se notifica a `LiquidationManager.onBalanceWentNegative(uuid)`. Una
segunda notificación mientras el jugador ya está en gracia es un no-op (no reinicia el contador).

El contador necesita resolución de **segundo real**, mucho más fina que el scheduler económico
general (`EconomicMasterScheduler`, ~30s de margen — justo el tamaño entero del plazo de gracia,
así que serviría demasiado tarde). Por eso hay un `LiquidationScheduler` dedicado, calcado de
`trade.TradeScheduler`: corre cada tick del servidor, pero solo hace trabajo real una vez cada ~20
ticks (1s), y es no-op instantáneo si nadie está en gracia.

**Pausado si te desconectas**, sin ningún hook especial de conexión/desconexión: cada pasada de
1s solo avanza el contador de un jugador si `server.getPlayerList().getPlayer(uuid)` no es null. Si
está offline, ese segundo simplemente no cuenta — nada que guardar ni restaurar.

**Cuenta atrás por chat** (`announceCountdown`, checkpoints fijos en tiempo real, no un mensaje por
segundo): al entrar en gracia, el tiempo completo ("Estas en banca rota. Tienes 30 segundos para
saldar tu deuda."); cada 10s a partir de ahí ("Te quedan 20 segundos..."); a los 10s exactos un
aviso dedicado ("En 10 segundos el estado embargara tus objetos mas valiosos."); y del segundo 5 al
1, un mensaje por segundo con solo el número. Los checkpoints se calculan sobre `remaining =
graceSeconds - elapsedBefore` tomado **antes** de incrementar el contador de ese tick, así que la
cuenta atrás final (5→1) siempre cae justo antes de que se ejecute la incautación en el mismo
segundo que llega a 0 - funciona igual con cualquier `graceSeconds` configurado, no solo con 30
(con un plazo corto de pruebas simplemente no le da tiempo a disparar todos los checkpoints).

Si el saldo vuelve a ≥0 antes de que se cumpla `graceSeconds`, el plazo se cancela sin más. Si se
agota, `LiquidationSeizureLogic.collectSeizable` recorre las 6 ranuras de equipo
(`LivingEntity#getItemBySlot`/`setItemSlot` — API estable independientemente de cómo el
`Inventory` interno guarde las cosas) más las 36 ranuras tradicionales del inventario principal
(deliberadamente no todo `inventory.getContainerSize()`, para no contar dos veces una ranura que
algunos layouts también exponen ahí), incautando todo lo que sea `ArmorItem`, `SwordItem`,
`AxeItem`, `PickaxeItem`, `ShovelItem`, `HoeItem`, `BowItem`, `CrossbowItem`, `TridentItem`,
`ShieldItem` o `MaceItem`. El saldo se fija a 0 exacto.

**Equipado y suelto van sin distinción a la misma lista de candidatos** de la votación - se
consideró excluir lo equipado de la subasta y devolverlo de inmediato, pero eso hacía que la
incautación de lo equipado fuera un ida-y-vuelta sin efecto real (se te quitaba y se te devolvía en
el mismo instante); descartado explícitamente por el usuario. Cada ítem incautado sí recuerda de
dónde salió (`LiquidationSeizureLogic.SeizedItem(stack, originSlot)`, `originSlot` null si venía suelto
del inventario) - eso no afecta a la votación (equipado y suelto compiten igual), pero significa que
si un candidato equipado **no gana** la votación, vuelve directo a esa misma ranura de equipo en vez
de caer como ítem suelto en la mochila (ver "reequipado" más abajo). El mensaje al jugador nombra
exactamente lo incautado (`"Netherite Sword x1, Diamond Pickaxe x1"`, vía `describeItems`), no una
frase genérica fija — antes decía siempre "tu armadura, armas y herramientas" aunque la víctima
solo llevara encima un único ítem, lo que hacía parecer que se había perdido más de lo real. Tampoco
repite "se agotó tu plazo de gracia" (ya lo anunció de sobra la cuenta atrás) - va directo a los
ítems. Cierra con una línea de sabor fija ("Quien avisa no es traidor. Mas suerte para la proxima.").

### Bloqueos durante la gracia

`LiquidationBlockListener` (mismo patrón de cancelación que `shop.ShopProtectionListener`) impide, solo
mientras `isInGracePeriod(uuid)`:
- **Recibir dinero de otro jugador**: `/pay` (`PayCommand`) y la pata de dinero de `/trade`
  (`TradeSession.complete`) rechazan la operación si el **receptor** está en gracia.
- **Tirar objetos**: `ItemTossEvent` (`net.neoforged.neoforge.event.entity.item`, no
  `entity.player` — ojo con el paquete). Su propio javadoc avisa de que cancelar el evento **no**
  deshace que el ítem ya se sacó del inventario, así que además de `setCanceled(true)` hay que
  devolverlo a mano con `placeItemBackInInventory` o el jugador simplemente lo pierde.
- **Abrir cualquier contenedor** - dos capas, no una:
  1. `onRightClickBlock` ya no comprueba una lista fija de clases (`ChestBlock`/`EnderChestBlock`
     originalmente) - eso es una batalla perdida contra shulkers, barriles y cualquier bloque de
     almacenamiento modded presente o futuro. En su lugar comprueba genéricamente
     `state.getMenuProvider(level, pos) != null` (verificado contra el propio `ChestBlock`
     decompilado: su `useWithoutItem` llama a `player.openMenu(menuProvider)` exactamente igual
     que cualquier otro bloque con menú), así que cualquier bloque que abra un menú se cancela
     antes de que el menú siquiera exista - sin parpadeo visual.
  2. Eso no cubre inventarios basados en **ítem** (una mochila que se abre con clic derecho o un
     keybind propio, sin pasar por un bloque) ni los menús propios de este mod (`/trade`, cuya
     ventana compartida es tan buen escondite como un cofre). Para esos existe `onContainerOpen`,
     enganchado a `PlayerContainerEvent.Open` - no cancelable, pero confirmado (decompilando
     `ServerPlayer#openMenu` parcheado por NeoForge) que se dispara justo después de **cualquier**
     `player.openMenu(...)`, venga de donde venga. Cierra el menú al instante con
     `player.closeContainer()` - un parpadeo de un tick en vez de nada, pero universal: cualquier
     mod que use la API estándar para abrir un menú queda cubierto sin necesitar conocerlo.

Vender en tiendas, cobrar salario y completar misiones **siguen funcionando** — las tiendas
funcionan por cartel (nunca abren ningún menú), así que ninguno de estos bloqueos les afecta.

### La votación (secreta, cambiable, dos condiciones para cerrar)

Al ejecutarse el embargo se crea un `AuctionVote` (uno por evento, id incremental) con los ítems
incautados como candidatos. En cuanto haya al menos `minVotersToClose` jugadores conectados sin
contar a la víctima, se anuncia una sola vez por chat con un botón `[Votar]` (mismo patrón de
[invitación pendiente](patronInvitacionPendiente.md) que `/trade`/`/subscribe`) que ejecuta
`/liquidation vote` — deliberadamente **fuera** de la raíz `/sc` (esa es solo para
administración/pruebas, no para algo que cualquier jugador debe poder correr).

El menú (`LiquidationVoteMenu`, calcado de `trade.TradeMenu`: mismo truco de `MenuType` vanilla +
`MirrorSlot` de solo lectura) muestra los objetos incautados en una fila compartida — una copia
idéntica para cualquiera que la abra — y una casilla de "tu voto" que es un campo **privado de esa
instancia de menú**, no compartido con nadie: como nadie más tiene una referencia a ese
`SimpleContainer`, es imposible que otro jugador vea tu voto, sin necesitar ninguna lógica extra de
visibilidad. Clicar un candidato llama a `LiquidationManager.castVote` (nunca mueve el ítem real) y
puede repetirse para cambiar de voto.

El cierre exige **ambas** condiciones a la vez: `votantes >= minVotersToClose` **y**
`díasDeJuegoTranscurridos >= minVoteGameDays` (vía `GameTime.currentDay`, mismo patrón que
`SalaryManager`/`SubscriptionManager`). Esto se comprueba en el scheduler general de ~30s
(`EconomicMasterScheduler`), no en el de por-tick — la precisión de días no la necesita.

**Ojo en pruebas:** `openVoteFor` siempre da la votación activa **más antigua** sin cerrar. Una
votación que nunca recibe votos (`votantes` se queda en 0) no cierra nunca — se queda ahí de por
vida, y sigue colándose por delante de embargos más recientes del mismo jugador. Esto ya pasó en
desarrollo: el botón `[Votar]` estuvo apuntando a `/sc embargo vote` (comando inexistente en aquel
momento - los literales todavía no eran "liquidation") varias sesiones, así que ninguna votación de
esa época pudo cerrarse nunca — al arreglar el botón, las
votaciones siguientes seguían enseñando esos objetos viejísimos primero, hasta limpiar a mano el
`activeVotes` de `embargo_data.json`.

**Ojo #2:** `minVoteGameDays` se mide con `GameTime.currentDay` — tiempo real de servidor
**acumulado** (1 día de juego = 20 minutos de uptime), no fecha del calendario ni `/time set`. En
una sesión de dev corta, votar ya no basta: aunque `minVotersToClose` se cumpla al instante (se ve
reflejado en `votesByVoter` en cuanto se vota, confirmado leyendo `embargo_data.json` en caliente),
`minVoteGameDays` puede seguir sin cumplirse durante horas de pruebas reales. Para eso existe
`/sc liquidation close <player>` (ver Comandos) — se salta ambas condiciones a la vez.

**Empate:** gana quien alcanzó ese número de votos primero. Cada candidato guarda su marca de agua
de votos más alta (`highWaterMark`) y el tick en el que la alcanzó (`reachedAtTick`); al cerrar, se
compara el recuento final y, entre los empatados, gana el de menor tick.

Al cerrar: el ítem ganador va a `AuctionPoolManager` (guarda quién lo perdió, cuándo); el resto se
devuelve directo al inventario de la víctima si está online, o se guarda en una lista de
devoluciones pendientes que se entrega automáticamente la próxima vez que inicie sesión
(`ServerLifecycleHandler.onPlayerLoggedIn` → `LiquidationManager.deliverPendingReturns`). El mensaje de
cierre solo dice "el resto se devolvió" si de verdad hubo más de un candidato incautado - si el
único ítem incautado fue directamente el ganador, dice "era el único objeto incautado" en vez de
insinuar una devolución que nunca existió.

**Reequipado, no solo devuelto:** `LiquidationManager.giveBack` (compartido por el cierre de votación y
por `deliverPendingReturns`) mira el `originSlot` de cada ítem devuelto - si venía de una ranura de
equipo **y esa ranura sigue vacía**, lo pone ahí directamente (`player.setItemSlot`) en vez de
soltarlo como ítem suelto en la mochila. Si la víctima ya se puso otra cosa en esa ranura mientras
tanto, nunca se la quita - cae a la mochila igual que un ítem que nunca estuvo equipado. Sin esto,
una armadura completa volvía como cuatro piezas sueltas en el inventario en vez de puesta.

**Bug corregido - "Air x0" en el mensaje de cierre:** el mensaje de cierre se construía **después**
de llamar a `returnItems`, pero `Inventory#placeItemBackInInventory` muta el propio `ItemStack` al
insertarlo (lo va vaciando con `split()` a medida que lo coloca) - para cuando el mensaje leía esos
mismos objetos para describirlos, ya estaban a 0, así que el resto devuelto salía como "Air x0" en
vez del ítem real. Se corrigió construyendo el texto del mensaje **antes** de llamar a
`returnItems`, sobre los `ItemStack` todavía intactos.

### La subasta con pujas

`AuctionPoolManager` es una lista persistida donde **un ítem a la vez** está en puja activa
(`FrontAuction`, siempre `items.get(0)`) — mismo espíritu de "una cosa activa a la vez" que ya usa
`AuctionVote` para la votación de qué se incauta. A diferencia de esa votación, **nada se abre
solo**: cuando un ítem gana la votación (`LiquidationManager.closeVote` → `AuctionPoolManager.add`),
simplemente se suma a la pool y ahí se queda - la única forma de que empiece a pujarse es que
alguien lo elija en el puesto de subastas (ver más abajo). Se descartó a propósito un diseño
anterior donde `add()` abría puja sola en cuanto la pool dejaba de estar vacía - el usuario quería
poder **elegir** qué se subasta, no que el orden de incautación decidiera por él.

**El "puesto de subastas" - multibloque + aldeano, no un comando.** Estructura confirmada con el
usuario (`AuctionStandListener`, mismo truco que usa vanilla para el Iron Golem/Snow Golem -
`CarvedPumpkinBlock`, verificado decompilándolo). Al colocar el atril (la pieza que completa la
estructura) se comprueba la forma con un `BlockPatternBuilder` - si encaja, aparece un `Villager`
real dentro:
- `setNoAi(true)` para que no se mueva, `setInvulnerable(true)` para que no lo puedan matar,
  `setPersistenceRequired()` para que nunca desaparezca por estar lejos - sin necesidad de una
  entidad custom, todo lo demás (render, guardado, ...) lo sigue llevando vanilla.
- Se marca con una etiqueta en `getPersistentData()` (`sheyito_auction_stand`) para reconocerlo -
  así `onVillagerInteract` (enganchado a `PlayerInteractEvent.EntityInteract`, cancelable,
  confirmado que se dispara sea cual sea el aldeano) puede cancelar su comercio vanilla solo para
  este aldeano concreto y abrir el menú de selección en su lugar, sin tocar ningún otro aldeano del
  mundo.
- `setNoAi(true)` también desactiva el goal selector entero, incluido el de mirar a los jugadores
  cercanos - sin nada más, el aldeano se quedaría mirando fijo hacia donde lo dejó `moveTo`.
  `onVillagerTick` (`EntityTickEvent.Post`, cada 4 ticks) le hace buscar al jugador más cercano
  (`Level.getNearestPlayer`, radio 8 bloques) y girar hacia él con `lookAt(Anchor.EYES, ...)` - a
  propósito **no** se calcula a partir de `BlockPatternMatch.getForwards()` (la orientación con la
  que encajó la estructura), para que mire de verdad al jugador sea cual sea la orientación con la
  que se construyó el puesto - pedido explícito del usuario, que no quería una cara fija.
- Suenan partículas (`ParticleTypes.HAPPY_VILLAGER`) y un sonido (`SoundEvents.VILLAGER_CELEBRATE`)
  al crearse, pedido explícito del usuario. La estructura **no se consume** al crear el aldeano (a
  diferencia de un golem vanilla) - el puesto se queda en pie.

**La forma exacta, 3 filas (`.aisle(...)`) x 4 alturas x 3 de ancho:** la primera versión exigía
que la lana y las columnas estuvieran pegadas al propio atril - en la práctica, al construirlo de
verdad, **nunca encajaba** (`encaja=false` en todos los intentos). Se añadió un volcado temporal de
depuración (`dumpSurroundings`, ver el commit de "diagnóstico") que imprime en crudo qué bloque hay
en cada celda de una caja 5x5 alrededor del atril, sin dar por buena ninguna suposición sobre
rotación/offset (esa lógica vive en `buildPattern`, que podía ser justo el bug que se buscaba). El
volcado reveló que la estructura real construida en el mundo tenía otra forma: el atril solo con
una base de lana, una fila más allá una puerta de verdad (2 postes + techo alrededor de un hueco de
2 bloques, con lana entre los postes) y otra fila más allá una tercera columna suelta. En vez de
pedirle reconstruir, se cambió `buildPattern` para exigir exactamente esa forma - confirmado con el
usuario, que prefería adaptar el código a lo que ya tenía en pie:
```
aisle 0 (fila del atril)   aisle 1 (la puerta)   aisle 2 (columna suelta)
?  ?  ?                    S  S  S  (techo)      ?  S  ?
?  ?  ?                    S  _  S               ?  S  ?
?  L  ?                    S  _  S  (aparece el   ?  S  ?
?  W  ?  (suelo)           S  W  S   aldeano)      ?  S  ?
```
`S` = bloque de pie configurado, `W` = lana roja, `L` = atril, `_` = debe quedar vacío (el hueco),
`?` = no importa. El aldeano aparece en el hueco superior de la puerta
(`match.getBlock(1, 2, 1)`), no encima del atril.

**Bug corregido - solo encajaba mirando hacia el oeste (o el sur).** El usuario reportó en partida
que el puesto solo se dejaba construir en una orientación concreta - hasta entonces se asumía
(incorrectamente, ver el `BlockPatternBuilder` de arriba) que `BlockPattern#find` ya probaba todas
las rotaciones solo, igual que el Golem de Hierro. Rehaciendo a mano el álgebra de
`BlockPattern#translateAndRotate` (decompilado) se confirmó el motivo real: `find(level, pos)` solo
busca en una caja que se extiende hacia delante (+X/+Y/+Z) desde `pos`, combinada con las 24
rotaciones finger/thumb posibles - funciona para el Golem de Hierro porque su bloque disparador (la
calabaza) está en una esquina simétrica y extrema del patrón, así que esa caja "solo hacia delante"
siempre lo alcanza sea cual sea la rotación. El atril de este puesto NO está en una esquina extrema:
está a media altura (fila `j=2` de 4) y una fila hacia dentro desde el frente (aisle `k=0` de 3) -
con las dimensiones de este patrón concreto, el desplazamiento que hace falta para encontrar la
estructura solo cae dentro de esa caja "hacia delante" cuando se construye mirando al oeste o al
sur; mirando al norte o al este, `find()` fallaba silenciosamente cada vez.

Como el puesto siempre se construye en pie (nunca de lado ni al revés - no tiene sentido para un
atril), la corrección (`AuctionStandListener.findInAnyHorizontalOrientation`) no necesita las 24
rotaciones de vanilla: fija el eje "up" (`thumb`) a `Direction.UP` y prueba los 4 `finger`
horizontales (`N`/`S`/`E`/`O`), pero busca la posición de origen en **ambos signos** del
desplazamiento horizontal (no solo hacia delante) - el desplazamiento vertical sí puede seguir
buscándose solo hacia delante, porque con `thumb` fijo a arriba nunca sale negativo, sea cual sea
la orientación horizontal probada. Verificado con un test dedicado
(`AuctionStandListenerTest`) que arma un mundo falso (mock de `ServerLevel`, solo con
`getBlockState`/`hasChunkAt`) con la estructura construida mirando al norte y confirma dos cosas: el
propio `pattern.find()` de vanilla no la encuentra (documenta el bug) y
`findInAnyHorizontalOrientation` sí (documenta el arreglo) - más un caso mirando al este (la otra
orientación rota) y otro mirando al oeste (para no romper la que ya funcionaba).

Hablar con ese aldeano abre `AuctionStandSelectionMenu` (chest `MenuType`, calcado de
`LiquidationVoteMenu`): lista todo lo que hay en `AuctionPoolManager.list()`, un click sobre cualquier
ítem llama a `AuctionPoolManager.startAuction` - lo mueve a la cabeza de la lista (el resto se
reordena sin perder nada) y abre `FrontAuction` sobre él. Rechaza con un mensaje si ya hay una
subasta en curso (una a la vez), si el ítem elegido ya no está en la pool (menú desactualizado), o
si no hay al menos `LiquidationConfig.minPlayersToStartAuction` (2 por defecto) jugadores conectados
**sin contar a la víctima del ítem** - mismo criterio de conteo que `minVotersToClose` en la
votación de embargo (`AuctionPoolManager.enoughPlayersOnline`), pedido explícito del usuario para
que no se pueda dejar una subasta abierta sin nadie real alrededor para pujarla. A diferencia de la
votación (que solo retrasa el *anuncio* hasta que haya suficiente gente, la votación en sí ya
existe), aquí el arranque en sí se rechaza sin más - el ítem se queda esperando en la pool.

**Pujar - GUI, no comando de chat con una cifra.** El usuario pidió explícitamente que pujar fuera
"algo dinámico", no escribir un número: `/auction` abre `LiquidationAuctionMenu` (mismo patrón de
chest menu, un `SimpleContainer` de 27 slots reconstruido en cada apertura, sin sincronización en
vivo entre varios jugadores mirando a la vez — mismo trade-off "snapshot, no push" que ya acepta la
votación). Fila 0 muestra el ítem en subasta, fila 1 la puja más alta actual y quién va ganando (o
"Sin pujas todavía"), fila 2 un botón por cada incremento de `LiquidationConfig.bidIncrements` (puja
`pujaActual + incremento` al clicarlo) más un botón "puja tu saldo máximo". Cada click llama a
`AuctionPoolManager.placeBid` con la cantidad calculada **en el momento del click** (nunca con una
etiqueta cacheada), así que aunque la vista de alguien esté un pelín desactualizada, nunca puede
pujar por accidente una cantidad distinta de la que ve. La víctima original del ítem
(`PooledItem.seizedFromUuid`) no puede ni abrir el menú para su propio ítem - mismo criterio que ya
bloquea que la víctima vote en su propio embargo.

**Pujar retiene el dinero al instante** (`EconomyManager.take`, rechaza la puja si no llega el
saldo) - si luego alguien puja más, se devuelve íntegro al pujador anterior
(`EconomyManager.give`). Si la puja ganadora se mantiene hasta el cierre, ese dinero retenido
**se queda quemado tal cual** - `take()` ya lo sacó de la economía, no hace falta ningún paso
extra para "quemarlo" (confirmado con el usuario: igual que el IVA de transmisión o la renta de
force-load, el dinero de la subasta nunca se redistribuye a nadie, ni siquiera a la víctima
original - encaja con el "no hay reembolso ni marcha atrás" de todo el embargo).

**Anuncios con botón - solo al empezar y al pujar, nunca en la cuenta atrás.** Pedido explícito del
usuario: cuando `startAuction` tiene éxito (`AuctionPoolManager.announceStart`) y cada vez que
`placeBid` tiene éxito (`announceBid`), se manda un mensaje a **todos** los jugadores conectados con
un botón `[Pujar]` (mismo patrón de [invitación pendiente](patronInvitacionPendiente.md) que la
votación de embargo) que ejecuta `/auction` - así cualquiera puede saltar directo al menú de pujas
desde el chat. El mensaje privado que antes confirmaba "pujaste X"/"subasta empezada" al propio
jugador que actuó se quitó - el broadcast ya se lo dice a él también, y duplicarlo era ruido; los
mensajes privados que quedan (`LiquidationAuctionMenu`/`AuctionStandSelectionMenu.announceResult`)
son solo para los casos de **fallo** (puja insuficiente, subasta ya en curso, etc.), que a nadie más
le interesan.

**Cierre - cuenta atrás real de inactividad, no un plazo fijo.** Antes cerraba tras
`auctionDurationGameDays` de tiempo de juego sin importar la actividad; ahora, pedido explícito del
usuario, se recicla literalmente el mecanismo del plazo de gracia del embargo
(`LiquidationManager#tickGrace`/`announceCountdown`, ver más arriba): `AuctionPoolManager.tickInactivity`
corre en cada tick de servidor vía un scheduler dedicado (`AuctionScheduler`, calcado de
`LiquidationScheduler` - necesita resolución de segundo real, la cadencia de ~30s de
`EconomicMasterScheduler` llegaría demasiado tarde) pero solo hace trabajo real una vez cada ~20
ticks (1s), y es no-op instantáneo si no hay ninguna subasta abierta. Cada segundo sin una puja
nueva incrementa `FrontAuction.elapsedInactivitySeconds`; **cada puja lo resetea a 0**
(`placeBid`) - a diferencia del plazo de gracia (que solo se cancela pagando la deuda entera), aquí
cualquier puja, por pequeña que sea, reinicia el reloj. Al llegar a
`LiquidationConfig.auctionInactivitySeconds` (30 por defecto) sin que nadie puje, se cierra:
- **Con pujador**: el ítem sale de la pool y se entrega al ganador - directo al inventario si está
  online, o a una lista de entregas pendientes si no (mismo patrón que
  `LiquidationManager`/`deliverPendingReturns`, replicado dentro de `AuctionPoolManager` porque es un
  concepto propio de la pool - se entrega en `ServerLifecycleHandler.onPlayerLoggedIn` vía
  `deliverPending`).
- **Sin pujas**: el ítem no se destruye - se manda al final de la lista, a la espera de que alguien
  vuelva a elegirlo en el puesto de subastas.

Los mismos checkpoints fijos que la cuenta atrás del embargo (cada 10s, aviso dedicado a los 10s,
cuenta atrás final 5→1 por segundo) se reutilizan tal cual para anunciar cuánto queda
(`announceCountdown`, broadcast a todos), salvo el checkpoint del tiempo completo nada más
empezar/pujar - ese ya lo cubre el mensaje de inicio/puja, repetirlo sería redundante. A propósito
esta cuenta atrás **no lleva botón** - el usuario fue explícito en que el botón `[Pujar]` es solo
para el mensaje de inicio y los mensajes de puja, no para el simple recordatorio de tiempo.

En ningún caso se abre puja fresca sola - cerrar deja `FrontAuction` en `null` hasta la próxima
visita al puesto, sea sobre el mismo ítem o cualquier otro de la pool.

**Bug corregido (otra vez) - "Air x0" en el mensaje de cierre de la subasta.** Mismo bug que ya se
había corregido una vez en el cierre de la votación (ver más arriba), reaparecido aquí porque
`closeCurrentAuction` construía el mensaje **después** de llamar a `deliver`, que en el caso de
ganador online acaba llamando a `Inventory#placeItemBackInInventory` - muta el propio `ItemStack`
al insertarlo, así que para cuando el mensaje leía ese mismo objeto, ya estaba a 0 y anunciaba
"Air x0" en vez del ítem real (visto en partida). Se corrigió igual que la primera vez: construir
el texto del mensaje **antes** de llamar a `deliver`. `/sc liquidation withdraw`
(`LiquidationCommand.withdraw`) tenía el mismo problema por la misma razón (`placeItemBackInInventory`
antes de leer el `ItemStack` para el mensaje de éxito) y se corrigió a la vez, aunque nadie lo había
reportado todavía.

`/sc liquidation withdraw` (OP) sigue existiendo como válvula de escape de admin - saca el ítem de
la cabeza de la lista pase lo que pase con la subasta, pero si ese ítem tenía una puja activa,
primero le devuelve el dinero retenido al pujador (mismo `EconomyManager.give` que un "outbid") para
no dejar dinero atrapado sin ítem ni reembolso.

**No verificable en este entorno:** la posición exacta donde aparece el aldeano y todo lo que
depende de un `ServerLevel` real (partículas, sonido, `moveTo`, ...) necesitan probarse construyendo
el puesto de verdad en el juego - no hay forma de levantar un `ServerLevel` real en los tests
unitarios de este mod. La búsqueda del patrón en sí (`findInAnyHorizontalOrientation`) sí tiene
cobertura de test (`AuctionStandListenerTest`, ver más arriba) usando un mock de `ServerLevel` que
solo necesita responder a `getBlockState`/`hasChunkAt` - suficiente para la geometría, no para el
resto del ciclo de vida de la entidad.

### Persistencia de ítems reales

Ni el vault temporal ni la pool final podían usar Gson tal cual — `ItemStack` no es un POJO plano
(vive detrás de `DataComponentMap`), así que serializarlo con reflexión perdería encantamientos,
durabilidad y nombres personalizados. `ItemStackJson` (`util/ItemStackJson.java`) resuelve esto
puenteando el propio `ItemStack.CODEC` de vanilla con `JsonOps.INSTANCE` — que opera sobre el mismo
`com.google.gson.JsonElement` que ya usa `JsonFileUtil` — así que el objeto codificado se guarda
como un campo más de una clase de datos normal, sin ningún `TypeAdapter` custom. Verificado con un
test de round-trip dedicado (nombre personalizado, durabilidad, tamaño de stack).

## Comandos

Literales en inglés a propósito (pedido explícito del usuario: nada de castellano en los comandos
en sí, aunque todos los mensajes al jugador siguen en español) — el nombre interno de la feature
(clases, config, docs) sigue siendo "embargo", solo cambió lo que el jugador escribe.

- `/liquidation vote` (cualquier jugador elegible - no la víctima) — abre el menú de votación si
  hay una activa.
- `/auction` (cualquier jugador - no la víctima del ítem actual) — abre `LiquidationAuctionMenu`
  para pujar por el ítem en cabeza de la pool, si hay alguno. Raíz propia y no bajo `/liquidation`
  a propósito (`AuctionCommand`, pedido explícito del usuario, es el comando "por defecto" que se
  espera que la gente teclee) - es el mismo comando que ejecuta el botón `[Pujar]` de los mensajes
  de inicio/puja.
- **No hay comando para elegir qué se subasta** - eso es a propósito del "puesto de subastas"
  (atril + 3 columnas + techo, ver más arriba): hablar con el aldeano que aparece dentro abre
  `AuctionStandSelectionMenu`.
- `/sc liquidation withdraw` (OP nivel 2) — saca el siguiente ítem de la pool de subastas y lo
  entrega al admin que lo ejecuta, reembolsando primero cualquier puja activa sobre él.
- `/sc liquidation close <player>` (OP nivel 2) — fuerza el cierre de la votación **más antigua**
  de ese jugador ahora mismo, ignorando tanto `minVotersToClose` como `minVoteGameDays`
  (`LiquidationManager.forceCloseOldestVote`). Necesario porque `minVoteGameDays` se mide en tiempo
  real de servidor acumulado (`GameTime`, ver más abajo) - una sesión de pruebas corta puede no
  llegar nunca a acumular lo suficiente por más gente que vote. Sin votos emitidos, gana el
  candidato en el índice 0 (mismo criterio de empate de siempre). No hace nada si ese jugador no
  tiene ninguna votación activa.

El único ajuste de config es `config/sheyitoscurrency/embargo.json` (`enabled`, `graceSeconds`,
`minVotersToClose`, `minVoteGameDays`, `auctionInactivitySeconds`, `minPlayersToStartAuction`,
`bidIncrements`, `auctionStandBlockId`).

## Cómo se conecta con otras features

Es el consumidor de la transición ≥0→negativo en `EconomyManager.setBalance()` — genérico a
propósito, así que no le importa qué la causó. Hoy tiene dos disparadores reales: `/eco charge`
(admin) y la [renta progresiva sobre ganancias](rentaProgresiva.md) (gameplay real, la primera vía
orgánica). Este propio archivo no usa `EconomyManager.charge()` en ningún punto — solo reacciona a
que el saldo ya haya cruzado a negativo, sin importarle quién lo cruzó.
