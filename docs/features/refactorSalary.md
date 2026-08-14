# Rebalanceo del salario diario

**Estado:** implementado (2026-08-13).
**Código relacionado:** `SalaryConfig.java`, `SalaryManager.java`, `LevelCurve.java`.

## Qué es esto

El mod paga a cada jugador un salario automático cada cierto número de días de juego. Ese
salario no es igual para todos: cuanto más alto es tu nivel, más cobras, hasta llegar a un
techo. Esta feature ajustó **cuánto** se cobra en el extremo alto y **cuántos niveles** hacen
falta para llegar ahí.

## Qué cambió, en números

| | Antes | Ahora |
|---|---|---|
| Salario en nivel 0 | 10 SC/día | 10 SC/día (sin cambios) |
| Salario en el nivel máximo | 500 SC/día | 100 SC/día |
| Nivel máximo | 20 | 50 |

## Por qué se hizo este cambio

El diseño general del mod busca que el dinero circule bajo presión
constante, no que se acumule sin límite. Un techo de 500 SC/día hacía que, una vez un jugador
llegaba a nivel alto, el salario por sí solo generara mucho dinero fácil, entrando en tensión
con la filosofía de "nada se imprime sin que algo queme". Bajar el techo a 100 y, a la vez,
estirar la escalera a 50 niveles en vez de 20, logra dos cosas a la vez:

- El salario máximo pesa menos en la economía general.
- Llegar al tope se vuelve mucho más lento y raro, porque ahora hacen falta más del doble de
  niveles, y cada nivel adicional en la curva Fibonacci es exponencialmente más caro que el
  anterior (ver la siguiente sección).

## Cómo funciona el cálculo, explicado sin código

El salario de un jugador se calcula con una interpolación lineal simple entre dos puntos: "en
nivel 0 cobras la base" y "en el nivel máximo cobras el techo". Si estás a mitad de camino entre
ambos niveles, cobras a mitad de camino entre ambos importes. Por ejemplo, un jugador de nivel
10 antes estaba a mitad de camino de 20 (progreso 50%), así que cobraba 255 SC/día (a medio
camino entre 10 y 500). Ese mismo jugador de nivel 10 ahora solo está a un 20% del camino de 50
niveles, así que cobra 28 SC/día (a un 20% del camino entre 10 y 100) — mucho menos, porque
ahora "estar a mitad de camino" significa haber llegado al nivel 25, no al 10.

Subir de nivel no es gratis ni lineal: cada nivel nuevo exige una cantidad de experiencia que
crece siguiendo la sucesión de Fibonacci (1, 1, 2, 3, 5, 8, 13...). Como Fibonacci crece cada vez
más rápido, los primeros niveles se suben rápido, pero los últimos son brutalmente lentos de
alcanzar — a propósito. Con 50 niveles en vez de 20, ese tramo final "brutal" es mucho más largo,
así que casi ningún jugador va a ver el salario máximo de 100 SC/día en la práctica, salvo tras
mucho tiempo de juego activo.

## Qué NO cambió

- El salario base (10 SC/día en nivel 0) sigue igual.
- La cantidad de XP que se gana por cada moneda ganada (`xpPerCoin`) no se tocó.
- El intervalo de pago (cada cuántos días de juego se cobra) no se tocó.

## Impacto para el jugador

Un jugador nuevo no nota diferencia (el salario inicial es el mismo). Un jugador ya avanzado que
antes estaba cerca del techo de nivel 20 va a notar que su salario baja, porque ahora ese mismo
nivel está mucho más lejos del nuevo techo — tendrá que seguir subiendo de nivel para recuperar
un salario alto.
