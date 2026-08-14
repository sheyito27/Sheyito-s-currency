# Patrón: comandos con Brigadier

Usado por: `BalCommand`, `BalTopCommand`, `PayCommand`, `SubscribeCommand`, `TradeCommand`,
`BuyCommand`, `EcoCommand`, `EconomicMasterCommand`.

## Qué resuelve

Minecraft necesita conocer de antemano la "gramática" completa de cada comando (para
autocompletar y para dar errores de sintaxis automáticos) — no se parsea el texto a mano.

## Cómo funciona

Cada clase de comando expone un único `register(CommandDispatcher<CommandSourceStack>
dispatcher)`, llamado una vez al arrancar desde `CommandRegistrar.java` (el único sitio que
conoce a todas las clases de comando; cada comando en cambio solo conoce sus propias
dependencias).

`register()` construye un árbol encadenando `.then(...)`: `Commands.literal("palabra")` exige
texto fijo, `Commands.argument("nombre", tipo)` acepta un valor variable y lo guarda bajo ese
nombre (`IntegerArgumentType.integer(min)`, `DoubleArgumentType.doubleArg(min)`,
`GameProfileArgument.gameProfile()`, `EntityArgument.player()` son los tipos que usa este mod).
`.executes(Clase::metodo)` marca qué función correr cuando el jugador completa ese camino exacto
— es una referencia a método, no una llamada: no se ejecuta durante `register()`, solo se guarda
el puntero para cuando el comando se escriba de verdad.

Dentro del método que ejecuta el comando, `ctx.getSource()` da el `CommandSourceStack` (quién lo
ejecutó — jugador, consola...) y `TipoArgumentType.getValor(ctx, "nombre")` recupera lo que el
jugador escribió en cada argumento, usando el mismo nombre con el que se declaró en `register()`.

**Errores de sintaxis (`throws CommandSyntaxException`):** métodos como
`ctx.getSource().getPlayerOrException()` o el parseo interno de cada `ArgumentType` pueden lanzar
esta excepción. Ningún comando la atrapa — todos la dejan subir con `throws` en la firma. Brigadier
la atrapa de forma centralizada (en su propio dispatcher, no en código de este mod) y genera el
mensaje de error en rojo automáticamente; si el parseo de un argumento falla, el método
`.executes(...)` nunca llega a ejecutarse.
