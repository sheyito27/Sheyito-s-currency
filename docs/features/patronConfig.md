# Patrón: config autogenerada por feature

Usado por: `GeneralConfig`, `MobRewardsConfig`, `SalaryConfig`, `QuestRewardsConfig`,
`SubscriptionsConfig`, `ShopConfig`, `XpShopConfig`.

## Qué resuelve

Cada feature con algún número/flag ajustable expone eso en un JSON editable por el admin, sin
recompilar el mod ni tocar código.

## Cómo funciona

Cada feature tiene una clase `*Config.java` mínima: campos públicos con su valor por defecto ya
asignado, y un `defaults()` estático que devuelve una instancia nueva (`ShopConfig.java` es el
ejemplo más corto: un solo campo).

`ConfigManager.java` centraliza la carga: un campo `static volatile` por config, y `load()`
(`ConfigManager.java:33-43`) llama a `JsonFileUtil.loadOrCreate(ruta, Clase.class,
Clase::defaults)` por cada una — si el archivo existe y es válido lo lee, si no existe o está
corrupto lo genera con los defaults y lo guarda. Se ejecuta una vez al arrancar el servidor
(`ServerLifecycleHandler.onServerStarting`) y de nuevo cada vez que se ejecuta `/eco reload`
(`EcoCommand.reload`), sin reiniciar el servidor.

Cada config vive en su propio archivo dentro de `config/sheyitoscurrency/` (ver tabla en
`README.md`), y se accede desde cualquier sitio del mod vía un getter de `ConfigManager`
(`ConfigManager.salary()`, `ConfigManager.xpShop()`, etc.) — nunca se lee el JSON directamente
fuera de `ConfigManager`.
