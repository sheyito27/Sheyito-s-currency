# Embargo silencioso y brutal

**Estado:** implementado.
**Código relacionado:** `EmbargoConfig.java`, `EmbargoManager.java`, `AuctionVote.java`, `EmbargoScheduler.java`, `EmbargoSeizureLogic.java`, `EmbargoBlockListener.java`, `EmbargoVoteMenu.java`, `EmbargoCommand.java`, `AuctionPoolManager.java`, `ItemStackJson.java`.
**Patrones:** [manager](patronManager.md), [config](patronConfig.md), [comandos](patronComandos.md).

## Qué es esto

Cuando el saldo de un jugador se vuelve negativo, empieza a correr un plazo de gracia real (30
segundos por defecto, `graceSeconds`) para saldarlo. Si no lo hace a tiempo, el mod le **incauta**
del inventario todo lo que sea armadura, arma o herramienta (equipada y suelta), le devuelve el
saldo a exactamente 0, y guarda lo incautado hasta que la comunidad vota **una sola pieza** para
mandar a una pool de subastas — el resto se le devuelve. No hay reembolso ni marcha atrás: pagar
después de que se ejecute el embargo no recupera nada.

**Importante:** hoy no existe ninguna vía de gameplay real para caer en saldo negativo — el único
caller de `EconomyManager.charge()` es el admin-only `/eco charge`. Esta feature construye
únicamente la **reacción** a un saldo negativo, sea cual sea su origen; la futura feature de
"pagos obligatorios" será quien dispare deuda real de forma orgánica, sin tener que tocar nada de
esto cuando llegue — el enganche vive en el choke point genérico de `EconomyManager.setBalance()`.

## Cómo funciona

### El plazo de gracia (30s reales, pausado si estás offline)

`EconomyManager.setBalance()` es el único método por el que pasan `give`/`take`/`charge` — ahí, si
el saldo cruza de ≥0 a negativo, se notifica a `EmbargoManager.onBalanceWentNegative(uuid)`. Una
segunda notificación mientras el jugador ya está en gracia es un no-op (no reinicia el contador).

El contador necesita resolución de **segundo real**, mucho más fina que el scheduler económico
general (`EconomicMasterScheduler`, ~30s de margen — justo el tamaño entero del plazo de gracia,
así que serviría demasiado tarde). Por eso hay un `EmbargoScheduler` dedicado, calcado de
`trade.TradeScheduler`: corre cada tick del servidor, pero solo hace trabajo real una vez cada ~20
ticks (1s), y es no-op instantáneo si nadie está en gracia.

**Pausado si te desconectas**, sin ningún hook especial de conexión/desconexión: cada pasada de
1s solo avanza el contador de un jugador si `server.getPlayerList().getPlayer(uuid)` no es null. Si
está offline, ese segundo simplemente no cuenta — nada que guardar ni restaurar.

Si el saldo vuelve a ≥0 antes de que se cumpla `graceSeconds`, el plazo se cancela sin más. Si se
agota, `EmbargoSeizureLogic.collectSeizable` recorre las 6 ranuras de equipo
(`LivingEntity#getItemBySlot`/`setItemSlot` — API estable independientemente de cómo el
`Inventory` interno guarde las cosas) más las 36 ranuras tradicionales del inventario principal
(deliberadamente no todo `inventory.getContainerSize()`, para no contar dos veces una ranura que
algunos layouts también exponen ahí), incautando todo lo que sea `ArmorItem`, `SwordItem`,
`AxeItem`, `PickaxeItem`, `ShovelItem`, `HoeItem`, `BowItem`, `CrossbowItem`, `TridentItem`,
`ShieldItem` o `MaceItem`. El saldo se fija a 0 exacto.

### Bloqueos durante la gracia

`EmbargoBlockListener` (mismo patrón de cancelación que `shop.ShopProtectionListener`) impide, solo
mientras `isInGracePeriod(uuid)`:
- **Recibir dinero de otro jugador**: `/pay` (`PayCommand`) y la pata de dinero de `/trade`
  (`TradeSession.complete`) rechazan la operación si el **receptor** está en gracia.
- **Tirar objetos**: `ItemTossEvent` (`net.neoforged.neoforge.event.entity.item`, no
  `entity.player` — ojo con el paquete). Su propio javadoc avisa de que cancelar el evento **no**
  deshace que el ítem ya se sacó del inventario, así que además de `setCanceled(true)` hay que
  devolverlo a mano con `placeItemBackInInventory` o el jugador simplemente lo pierde.
- **Abrir cofres o el ender chest**: se bloquea el acceso completo (no solo "meter cosas"), más
  simple y robusto que intentar permitir sacar pero no depositar.

Vender en tiendas, cobrar salario y completar misiones **siguen funcionando** — nada de eso pasa
por ninguno de estos tres bloqueos.

### La votación (secreta, cambiable, dos condiciones para cerrar)

Al ejecutarse el embargo se crea un `AuctionVote` (uno por evento, id incremental) con los ítems
incautados como candidatos. En cuanto haya al menos `minVotersToClose` jugadores conectados sin
contar a la víctima, se anuncia una sola vez por chat con un botón `[Votar]` (mismo patrón de
[invitación pendiente](patronInvitacionPendiente.md) que `/trade`/`/subscribe`) que ejecuta
`/embargo vote` — deliberadamente **fuera** de la raíz `/sc` (esa es solo para
administración/pruebas, no para algo que cualquier jugador debe poder correr).

El menú (`EmbargoVoteMenu`, calcado de `trade.TradeMenu`: mismo truco de `MenuType` vanilla +
`MirrorSlot` de solo lectura) muestra los objetos incautados en una fila compartida — una copia
idéntica para cualquiera que la abra — y una casilla de "tu voto" que es un campo **privado de esa
instancia de menú**, no compartido con nadie: como nadie más tiene una referencia a ese
`SimpleContainer`, es imposible que otro jugador vea tu voto, sin necesitar ninguna lógica extra de
visibilidad. Clicar un candidato llama a `EmbargoManager.castVote` (nunca mueve el ítem real) y
puede repetirse para cambiar de voto.

El cierre exige **ambas** condiciones a la vez: `votantes >= minVotersToClose` **y**
`díasDeJuegoTranscurridos >= minVoteGameDays` (vía `GameTime.currentDay`, mismo patrón que
`SalaryManager`/`SubscriptionManager`). Esto se comprueba en el scheduler general de ~30s
(`EconomicMasterScheduler`), no en el de por-tick — la precisión de días no la necesita.

**Empate:** gana quien alcanzó ese número de votos primero. Cada candidato guarda su marca de agua
de votos más alta (`highWaterMark`) y el tick en el que la alcanzó (`reachedAtTick`); al cerrar, se
compara el recuento final y, entre los empatados, gana el de menor tick.

Al cerrar: el ítem ganador va a `AuctionPoolManager` (guarda quién lo perdió, cuándo); el resto se
devuelve directo al inventario de la víctima si está online, o se guarda en una lista de
devoluciones pendientes que se entrega automáticamente la próxima vez que inicie sesión
(`ServerLifecycleHandler.onPlayerLoggedIn` → `EmbargoManager.deliverPendingReturns`).

### La pool de subastas: solo almacenamiento, nada automático

`AuctionPoolManager` es deliberadamente tonto: una cola FIFO persistida, sin lógica de subasta
alguna. `/sc embargo retirar` (OP, bajo la raíz admin `/sc`) saca el ítem más antiguo y se lo da al
admin que lo ejecuta — a partir de ahí, la comunidad decide qué hacer con él (montar una casa de
subastas, repartirlo, lo que sea). Sin este comando el ítem quedaría atrapado para siempre.

### Persistencia de ítems reales

Ni el vault temporal ni la pool final podían usar Gson tal cual — `ItemStack` no es un POJO plano
(vive detrás de `DataComponentMap`), así que serializarlo con reflexión perdería encantamientos,
durabilidad y nombres personalizados. `ItemStackJson` (`util/ItemStackJson.java`) resuelve esto
puenteando el propio `ItemStack.CODEC` de vanilla con `JsonOps.INSTANCE` — que opera sobre el mismo
`com.google.gson.JsonElement` que ya usa `JsonFileUtil` — así que el objeto codificado se guarda
como un campo más de una clase de datos normal, sin ningún `TypeAdapter` custom. Verificado con un
test de round-trip dedicado (nombre personalizado, durabilidad, tamaño de stack).

## Comandos

- `/embargo vote` (cualquier jugador elegible - no la víctima) — abre el menú de votación si hay
  una activa.
- `/sc embargo retirar` (OP nivel 2) — saca el siguiente ítem de la pool de subastas y lo entrega
  al admin que lo ejecuta.

El único ajuste de config es `config/sheyitoscurrency/embargo.json` (`enabled`, `graceSeconds`,
`minVotersToClose`, `minVoteGameDays`).

## Cómo se conecta con otras features

Es el primer consumidor real de la transición ≥0→negativo en `EconomyManager.setBalance()` — hoy
solo alcanzable a mano vía `/eco charge`, pero el enganche es genérico a propósito para que la
futura feature de "pagos obligatorios" no necesite tocar nada de este archivo. No usa
`EconomyManager.charge()` en ningún punto (ese método sigue siendo exclusivo de `/eco charge`).
