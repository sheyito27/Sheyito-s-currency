# Propuestas — Sheyito's Currency v2

## Ya implementado (base actual)

- [x] **Saldo, /bal y /baltop**: Consulta tu saldo, el de otros y el ranking por patrimonio.
- [x] **Salario diario con niveles**: Cobro automático por días de juego, escala con nivel Fibonacci.
- [x] **/pay con transferencias P2P**: Envía saldo a otro jugador, sin sumar XP de nivel.
- [x] **/trade con GUI seguro**: Intercambio atómico de ítems y dinero sin riesgo de estafa.
- [x] **Suscripciones entre jugadores**: Ofrece tu servicio a tu precio; otros pagan cuota recurrente.
- [x] **Tiendas de cartel y cofre**: Vende o compra ítems automáticamente sin estar conectado.
- [x] **Caza de mobs opcional**: Whitelist de entidades que dan dinero al morir a manos de un jugador.
- [x] **Recompensa automática de FTB Quests**: Toda misión completada paga SC al equipo automáticamente.

## Presión

- [x] **Peaje de waystones**: Usar un waystone cobra SC (100 por defecto), bloqueando el teletransporte si no alcanza.
- [x] **Desbloqueo de dimensiones**: Entrar a Nether/End/dimensión modded cuesta SC una vez, para siempre.
- [ ] **Peajes de movilidad restantes**: /home, /back y /tpa aún no cobran SC.
- [x] **Renta de chunks**: Reclamar con FTB Chunks cuesta 1.000·n^1.5 SC (n = chunk que reclamás), pago único, no configurable (sin renta periódica todavía — ver "Día de Renta").
- [x] **Muerte**: Morir te hace perder 50% de tu saldo actual, sin riesgo de banca rota.
- [x] **Embargo silencioso y brutal**: Saldo negativo sin saldar en 30s (pausados offline) incauta armadura/armas/herramientas; votación secreta de la comunidad elige qué pieza va a la pool de subastas.
- [ ] **Día de Renta**: Cada 7 días cobra chunks, cuotas, suscripciones y deudas con countdown.
- [ ] **Descuento por pago anticipado**: Pagar la renta antes del día 5 ahorra un 10%.
- [x] **IVA de transmisión**: Comisión sobre /pay, el dinero de /trade, tiendas y suscripciones que quema transferencias (10% por defecto, doble corte: paga de más, recibe de menos).
- [ ] **Cuota de mantenimiento**: Coste periódico progresivo por patrimonio acumulado del jugador.

## Dopamina

- [ ] **Gacha con exclusivos no renovables**: Menú aleatorio; ediciones limitadas numeradas y , encantamientos op, loot de estructuras random, artefactos.
- [ ] **Ofertas flash**: Descuentos temporales con temporizador en gacha, XP y conveniencias.
- [x] **Compra de XP**: Intercambio directo de Sheyicoins por experiencia vanilla (`/buy xp`), 1:1 a propósito como sink.

## Salario

- [x] **Salario rebalanceado**: Base 10, techo 100 por día, XP mínima por moneda ganada.
- [ ] **Avance de salario**: Cobrar hoy lo proporcional al día vivido, perdiendo el salario completo.

## Estatus y cosméticos

- [ ] **Nombre con color y títulos**: Personalización del nombre sobre la cabeza y en la tablist.
- [ ] **Auras de partículas**: Efectos cosméticos por suscripción con expiración y renovación.
- [ ] **Títulos**: Tier de logros de patrimonio reflejados en un título al lado del nombre.

## Personalidad y narrativa

- [ ] **Voz**: El banquero narra cada cobro, embargo y evento con estilo propio.
- [ ] **Cartas Chance de sabor**: Eventos raros de bajo impacto con texto narrativo.
