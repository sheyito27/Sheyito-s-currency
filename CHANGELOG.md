# Changelog

Resumen de qué ha cambiado en Sheyito's currency, de más reciente a más antiguo.

## Versión 1.0.5

### Nuevo: peaje de movilidad (Waystones)
- Si tenés instalado el mod Waystones, usar un waystone cuesta 100 Sheyicoins por defecto (configurable en `waystone_toll.json`). No hace falta Waystones para nada más - sin él, el mod funciona exactamente igual. Si no te alcanza el saldo, se bloquea el teletransporte - no se cobra nada.

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
