# Changelog

Resumen de qué ha cambiado en Sheyito's currency, de más reciente a más antiguo.

## Versión 1.1.0

### Arreglado
- El botón `[Votar]` del aviso de embargo apuntaba a `/sc embargo vote` (no existe) en vez de `/embargo vote` - el click siempre fallaba con "Unknown or incomplete command".
- Los mensajes de incautación y de cierre de votación eran texto fijo genérico ("tu armadura, armas y herramientas", "el resto se devolvió") sin importar lo que realmente pasara - si la víctima solo tenía un ítem encima, sonaba como si hubiera perdido más de lo real, o como si algo se hubiera devuelto cuando no había "resto" que devolver. Ahora nombran exactamente lo incautado/devuelto.
- El mensaje de cierre de votación podía anunciar "Air x0" como el objeto devuelto en vez del nombre real - `Inventory#placeItemBackInInventory` vacía el propio `ItemStack` al insertarlo, y el mensaje se construía después de eso, leyendo el objeto ya vacío. Ahora se construye antes.
- Durante el periodo de gracia solo se bloqueaba abrir cofre/ender chest - un shulker, un barril, o cualquier contenedor de otro mod dejaban esconder el equipo igualmente. Ahora se bloquea cualquier bloque que abra un menú (comprobación genérica, no una lista de clases) más un cierre universal de cualquier ventana que se llegue a abrir (incluida la propia `/trade`), como red de seguridad para inventarios que no son bloques.
- Varios mensajes de chat del embargo se escribieron sin tildes - corregidos.
- El mensaje al intentar abrir cualquier contenedor en periodo de gracia mencionaba el "periodo de gracia" - ya no lo hace (el de tirar objetos sigue mencionándolo).
- El mensaje de cierre de la subasta (y el de `/sc liquidation withdraw`) también podían anunciar "Air x0" en vez del objeto real - mismo bug que el de la votación (arriba), reaparecido porque el mensaje se construía después de entregar el ítem. Corregido igual: se construye antes.
- El puesto de subastas solo se dejaba construir mirando hacia el oeste (o el sur) - vanilla's `BlockPattern#find` solo busca hacia delante desde el atril, y el atril de este puesto no está en una esquina extrema del patrón como sí lo está la calabaza del Golem de Hierro, así que mirando al norte o al este la búsqueda fallaba siempre. Ahora se busca en ambos sentidos del eje horizontal y funciona mirando a cualquiera de los 4 puntos cardinales.

### Nuevo: subasta con pujas sobre la pool de subastas
- El objeto que gana la votación de incautación entra en la pool, pero nada se subasta solo: hace
  falta construir un **puesto de subastas** (atril + 3 columnas + techo, bloque configurable con
  `auctionStandBlockId`) para que aparezca un aldeano fijo dentro (con partículas y sonido al
  crearse) - hablar con él abre un menú con todo lo que hay en la pool, y elegir un objeto ahí es
  la única forma de ponerlo en juego. El aldeano gira para mirar al jugador más cercano en vez de
  quedarse mirando a un punto fijo, sea cual sea la orientación con la que se construyó la
  estructura - y esa estructura misma se puede construir mirando a cualquiera de los 4 puntos
  cardinales (ver el arreglo de detección más abajo).
- Esa subasta sí es real, con pujas (`/auction`, menú tipo cofre con botones - nada de escribir
  cifras por chat). Un botón por cada incremento configurado (`bidIncrements`) puja
  `pujaActual + incremento`, más un botón para pujar el saldo máximo. Pujar retiene el dinero al
  instante; si te superan, se devuelve íntegro.
- Al empezar una subasta y en cada puja se anuncia por chat a todo el servidor con un botón
  `[Pujar]` que abre el menú directamente - el mismo `/auction` de arriba.
- Elegir un ítem en el puesto se rechaza si no hay al menos `minPlayersToStartAuction` (2 por
  defecto) jugadores conectados sin contar a la víctima del ítem - mismo criterio de conteo que
  `minVotersToClose` en la votación de embargo, para no dejar una subasta abierta sin nadie
  alrededor para pujarla.
- La puja se cierra si pasan `auctionInactivitySeconds` segundos reales (30 por defecto) sin que
  nadie puje - cada puja reinicia esa cuenta atrás a 0 (recicla la misma lógica del plazo de gracia
  del embargo: avisos cada 10s, aviso a los 10s y cuenta atrás final 5→1, esta vez sin botón). Si
  ganó alguien, se lleva el ítem y su dinero se queda quemado - nunca se redistribuye a nadie, ni
  siquiera a la víctima original, coherente con el "no hay reembolso ni marcha atrás" de todo el
  embargo. Si nadie pujó, el ítem vuelve a esperar en la pool - nada se reabre solo, hay que volver
  a elegirlo en el puesto.
- `/sc liquidation withdraw` sigue existiendo como válvula de escape de admin, ahora reembolsando
  primero cualquier puja activa sobre el ítem que saca.

### Cambiado: embargo con cuenta atrás real
- El plazo de gracia ahora avisa por chat en cuenta atrás real: el tiempo completo al entrar en banca rota, cada 10 segundos, un aviso dedicado a los 10 segundos, y un mensaje por segundo del 5 al 1 justo antes de la incautación.
- El mensaje de incautación ya no repite "se agotó tu plazo de gracia" (la cuenta atrás ya avisó de sobra) - va directo a qué se incautó.
- Un ítem incautado que llevabas equipado y no gana la votación (o vuelve por estar offline) se **reequipa** directamente en su ranura si sigue libre, en vez de caer como ítem suelto en la mochila.
- Nuevo comando de dev `/sc liquidation close <player>`: fuerza el cierre de la votación de embargo más antigua de ese jugador, saltándose el mínimo de votantes y de días de juego (que se miden en tiempo real de servidor acumulado - una sesión de pruebas corta puede no acumular suficiente aunque ya se haya votado).
- Los comandos del embargo pasan de español a inglés: `/embargo vote` → `/liquidation vote`, `/sc embargo retirar` → `/sc liquidation withdraw`. Solo cambian los literales que se escriben - los mensajes del mod al jugador siguen en español.
- Las clases Java de la feature pasan de `Embargo*` a `Liquidation*` (`EmbargoManager` → `LiquidationManager`, `EmbargoConfig` → `LiquidationConfig`, ...) y el paquete de `embargo` a `liquidation`, para que coincidan con el nombre que ya usan los comandos. Cambio puramente interno: la carpeta de config (`config/sheyitoscurrency/embargo.json`) y la de datos (`embargo_data.json`) no se tocaron a propósito, para no dejar huérfano el config/mundo de un servidor de pruebas ya en marcha; la ficha de esta feature sigue viviendo en `docs/features/embargoDeudas.md` por lo mismo.

### Nuevo: renta progresiva sobre ganancias
- Cada 7 días de juego (`rent.json`, `intervalGameDays`) se cobra un porcentaje sobre lo que ganaste en ese periodo (no tu patrimonio total): 1-10K → 10%, 10K-100K → 20%, 100K-1M → 30%, 1M+ → 40% (tope). Tipo plano por tramo, no marginal.
- Es ganancia bruta acumulada (cada ingreso vía `EconomyManager.give()`), no un balance neto: si ganas 10.000 en la semana pero por separado pierdes 20.000, igual se cobra el 10% de los 10.000 ganados - perder saldo es gasto, ajeno a esta renta, nunca compensa una ganancia.
- A diferencia de cualquier otro cobro del mod, este puede dejarte con saldo negativo (usa `charge()`, no `take()`) - es la primera vía de gameplay real que dispara el plazo de gracia del embargo por deuda.
- Comando de dev: `/sc rent force <player>` fuerza el cobro de ambas rentas (ganancias + force-load) sin esperar 7 días de juego reales.

### Nuevo: renta de force-load de chunks (FTB Chunks)
- Force-loadear un chunk sigue siendo gratis al activarlo, pero cada 7 días de juego se cobra `forceLoadRentBase * n^1.5` (base 10, `n` = chunks force-loaded ahora mismo) por jugador - también estando desconectado. Si no cubres el total, se descargan todos tus chunks force-loaded de golpe: inmediato si estás online, en cuanto te reconectes si no.

### Cambiado
- `ChunkClaimManager` se renombró a `ChunkClaimRegistry` al absorber el recuento de chunks force-loaded (mismo `chunk_claim_data.json`, sin pérdida de datos al actualizar).

## Versión 1.0.9

### Nuevo: embargo silencioso y brutal
- Si tu saldo se vuelve negativo (hoy solo posible vía `/eco charge`), tienes 30 segundos reales de gracia (pausados si te desconectas) para saldarlo. Mientras tanto no puedes recibir dinero de otros jugadores, tirar objetos, ni abrir cofres/ender chest.
- Si se agota el plazo: se incauta del inventario (equipado y suelto) toda armadura, arma o herramienta, el saldo vuelve a 0, sin marcha atrás.
- Los objetos incautados van a una votación secreta y cambiable (`/embargo vote`, menú tipo cofre) entre los jugadores conectados (excluye a la víctima) para elegir cuál se manda a la pool de subastas del servidor - el resto se devuelve al cerrar. La votación cierra solo con suficientes votos y suficientes días de juego a la vez; un empate lo gana quien alcanzó ese número de votos primero.
- `/sc embargo retirar` (OP): saca el siguiente ítem de la pool de subastas - es la única forma de que salga, nada es automático.
- Configurable en `embargo.json` (`graceSeconds`, `minVotersToClose`, `minVoteGameDays`).

## Versión 1.0.8

### Nuevo: IVA de transmisión
- `/pay`, el dinero de `/trade`, comprar/vender en tiendas de cartel y cada cobro de una suscripción (inicial y renovaciones) queman un porcentaje configurable (`taxPercent`, 10% por defecto, `transmission_tax.json`) en ambos lados a la vez: el pagador paga de más, el receptor recibe de menos. Con el 10% por defecto, una transacción de 100 SC hace que el pagador pague 110 y el receptor reciba 90. No afecta a `/eco`, `/sc reward`, salario, caza de mobs, ni a los peajes de waystones/dimensiones/chunks.

### Arreglado
- El README describía el precio de reclamar un chunk como "escala al cuadrado" - la fórmula real es `n^1.5`.

## Versión 1.0.7

### Arreglado: recuento de chunks reclamados
- Desreclamar un chunk ahora sí baja el recuento (antes solo subía, así que el precio del siguiente reclamo se calculaba sobre un total histórico en vez de los chunks que tenías reclamados en ese momento).

### Nuevo: `/sc chunk reset <jugador>`
- Comando de dev (OP) para poner a 0 el recuento de chunks reclamados de un jugador, sin reembolsar nada - para reprobar la curva de precio sin desreclamar chunk a chunk.

### Cambiado: comandos de administración/dev bajo `/sc`
- `/sheyitoscurrency reward` pasa a ser `/sc reward`.
- `/dimension lock` pasa a ser `/sc dimension lock`.
- Todo comando de administración/dev de este mod vive ahora bajo la raíz compartida `/sc`.

## Versión 1.0.6

### Nuevo: renta de chunks (FTB Chunks)
- Si tienes instalado el mod FTB Chunks, reclamar un chunk cuesta Sheyicoins - una sola vez por chunk, sin renta periódica todavía. El precio no es un valor fijo: escala como `n^1.5` con cada chunk que tengas reclamado *ahora mismo* (1.000 el primero, ~2.828 el segundo, ~5.196 el tercero, ~31.623 el décimo...) y no es configurable, a propósito, para desincentivar acaparar territorio sin volverse inalcanzable. Desreclamar un chunk baja el recuento (sin reembolso), así que el precio del siguiente reclamo refleja lo que tenés en ese momento, no un total histórico. No hace falta FTB Chunks para nada más - sin él, el mod funciona exactamente igual. Si no te alcanza el saldo, el reclamo se bloquea - no se cobra nada. Este mod no protege ni reclama chunks, eso lo hace FTB Chunks; solo cobra y lleva la cuenta.

## Versión 1.0.5

### Nuevo: peaje de movilidad (Waystones)
- Si tienes instalado el mod Waystones, usar un waystone cuesta 100 Sheyicoins por defecto (configurable en `waystone_toll.json`). No hace falta Waystones para nada más - sin él, el mod funciona exactamente igual. Si no te alcanza el saldo, se bloquea el teletransporte - no se cobra nada.

### Nuevo: desbloqueo de dimensiones
- Viajar a cualquier dimensión que no sea el Overworld (Nether, End, o cualquier dimensión modded - se detectan todas solas) cuesta 5000 Sheyicoins por defecto la primera vez (configurable en `dimension_unlock.json`). Si no te alcanza, el portal no te deja pasar y te quedas en el Overworld. Una vez pagas, esa dimensión queda desbloqueada para siempre. El mensaje dice qué dimensión es, en morado.
- `/dimension lock <jugador> <dimension>` (OP): revierte el desbloqueo de un jugador para poder reprobar el flujo sin reiniciar el mundo.

### Arreglado
- Los mensajes del peaje de Waystones y del desbloqueo de dimensiones repetían "Sheyicoins" dos veces (`Money.format()` ya lo incluye).

### Quitado: `/debt` y la deuda con plazo
- Se eliminó el comando `/debt` y toda la infraestructura que llevaba la cuenta de un plazo para pagar. Un saldo negativo (hoy solo posible vía `/eco charge`, herramienta de admin) ya no es un estado aparte con vencimiento - es simplemente saldo negativo, y se consulta con `/bal` como cualquier otro.

## Versión 1.0.4

### Nuevo: penalización por muerte
- Morir te hace perder automáticamente el 50% de tu Sheyicoins actuales (configurable en `debt.json`, `penaltyPercent`). Al ser un porcentaje de tu propio saldo, nunca te deja en negativo ni rompe la banca.

## Versión 1.0.3

### Nuevo: comprar experiencia (`/buy xp`)
- `/buy xp <cantidad>` cambia Sheyicoins por puntos de experiencia de Minecraft (la barra verde de encantar), a razón de 1 moneda por punto.

### Salario diario
- Rebalanceado: el salario máximo baja de 500 a 100 Sheyicoins/día, pero ahora hacen falta 50 niveles en vez de 20 para llegar al tope - subir hasta arriba del todo es más lento.

### Suscripciones (`/subscribe`)
- Ya no cobra al instante. Ahora `/subscribe <jugador> <dinero> <tiempo> [motivo]` envía una propuesta - igual que `/trade` - que el jugador debe aceptar con `/subscribe accept` (hay un botón para hacerlo directo desde el chat) o rechazar con `/subscribe deny`. No se cobra nada hasta que la acepta.

### Arreglado
- Solucionado un cuelgue que le pasaba a algún jugador al matar mobs, si tenía el mod instalado también por su cuenta en el cliente (no hace falta, el mod solo va en el servidor).

## Versión 1.0.2

### Suscripciones (`/subscribe`)
- Ahora es `/subscribe <jugador> <dinero> <tiempo> [motivo]`: eliges cuánto te paga esa persona y cada cuántos días se renueva el cobro, en el mismo comando.
- `/subscribe providers`: lista numerada de a quién le pagas tú (para poder cancelar).
- `/subscribe cancel <numero>`: cancela una suscripción de esa lista.
- `/subscribe clients`: quién te está pagando a ti.
- Arreglado: el comando cobraba al revés. Ahora, cuando ejecutas `/subscribe`, el jugador que pones en el comando es quien paga, y tú eres quien cobra.

### Intercambios (`/trade`)
- Ya no se deposita dinero metiendo lingotes en la ventana de intercambio (daba problemas). Ahora se hace directo en el comando: `/trade <jugador> <dinero> [mensaje]`. El dinero y el mensaje se ven en la invitación antes de aceptar.
- Si el dinero prometido ya no está disponible al cerrar el trato, el intercambio se cancela y se devuelve todo, en vez de completarse a medias.

### Tiendas
- Ya se puede editar un cartel de tienda después de haberlo colocado (antes solo servía si lo escribías bien a la primera).
- Los cofres de tienda ya no se pueden abrir directamente por cualquiera - solo el dueño o un admin. El resto tiene que usar el cartel para comprar o vender.
- Los mensajes de compra/venta ahora dicen también cuánto ha costado, no solo cuántos items.

### Otros
- El mensaje de intercambio completado también dice cuánto dinero se incluyó.
- Corregido un fallo de ortografía: "Sheyicoins añadidas" (antes decía "añadido").

## Versión 1.0.1

- La moneda se llama **Sheyicoins**.
- Las misiones de FTB Quests dan dinero automáticamente al completarse, sin tener que configurar nada misión por misión.
- Sonidos nuevos: uno cuando una compra/venta/intercambio sale bien, otro cuando falla.
- Matar mobs ya no da dinero por defecto (se puede activar en la configuración).

## Versión 1.0.0 - Lanzamiento inicial

- Saldo por jugador, con `/bal`, `/baltop` y ver el saldo/nivel de otros jugadores.
- Salario automático que sube con tu nivel.
- Recompensas por matar mobs (configurable).
- Suscripciones entre jugadores.
- `/trade`: intercambio seguro de items entre dos jugadores con ventana tipo cofre.
- Tiendas con cartel + cofre: coloca un cartel con el precio y la gente puede comprar/vender directamente.
- Todo en español.
