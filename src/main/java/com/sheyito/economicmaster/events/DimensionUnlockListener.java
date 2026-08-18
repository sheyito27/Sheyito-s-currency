package com.sheyito.economicmaster.events;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.DimensionUnlockConfig;
import com.sheyito.economicmaster.dimension.DimensionUnlockManager;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;

import java.util.UUID;

/**
 * Gates travel to any dimension other than the overworld behind a one-time payment. Unlike the
 * Waystones toll, this is a core NeoForge event ({@link EntityTravelToDimensionEvent}, part of
 * the mod loader itself) - no soft-dependency/{@code compileOnly} isolation needed, it's always
 * safe to reference.
 *
 * <p>Mirrors {@link MobKillListener}'s client-side/config-null guards - see that class for why
 * they're needed even though this mod is server-only.
 */
public class DimensionUnlockListener {

    @SubscribeEvent
    public void onEntityTravelToDimension(EntityTravelToDimensionEvent event) {
        Entity entity = event.getEntity();
        if (entity.level().isClientSide()) {
            return;
        }

        DimensionUnlockConfig config = ConfigManager.dimensionUnlock();
        if (config == null || !config.enabled || EconomyManager.get() == null || DimensionUnlockManager.get() == null) {
            return;
        }

        if (!(entity instanceof ServerPlayer player)) {
            return;
        }

        boolean allowed = handleTravel(EconomyManager.get(), DimensionUnlockManager.get(), config, player, event.getDimension());
        if (!allowed) {
            event.setCanceled(true);
        }
    }

    /**
     * Extracted from {@link #onEntityTravelToDimension} so it can be exercised in a unit test
     * without needing a real event, mirroring the plain-value-in/out style the rest of this
     * mod's managers use.
     *
     * @return true if the travel should proceed (overworld, already unlocked, or just paid for);
     * false if it must be blocked for insufficient funds - the player stays wherever they were,
     * which for a standard Nether/End portal is the overworld they were already standing in.
     */
    static boolean handleTravel(EconomyManager economy, DimensionUnlockManager unlocks, DimensionUnlockConfig config,
                                 ServerPlayer player, ResourceKey<Level> targetDimension) {
        if (targetDimension.equals(Level.OVERWORLD)) {
            return true;
        }

        UUID uuid = player.getUUID();
        if (unlocks.isUnlocked(uuid, targetDimension)) {
            return true;
        }

        Component dimensionName = dimensionDisplayName(targetDimension);

        if (!economy.take(uuid, config.price)) {
            player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fNecesitas " + Money.format(config.price) + " para desbloquear ")
                    .append(dimensionName)
                    .append(Component.literal("§f. Te quedas en el Overworld.")));
            return false;
        }

        unlocks.unlock(uuid, targetDimension);
        player.sendSystemMessage(Component.literal("§6[Sheyito's currency] §f-" + Money.format(config.price) + ". ")
                .append(dimensionName)
                .append(Component.literal("§f desbloqueada para siempre - ya puedes entrar cuando quieras.")));
        return true;
    }

    /**
     * Human-readable, colored dimension name with no per-dimension hardcoding: vanilla's own
     * "dimension.&lt;namespace&gt;.&lt;path&gt;" translation key (e.g. "dimension.minecraft.the_nether" -&gt;
     * "The Nether"). Any well-behaved modded dimension that registers the same key convention
     * gets a proper name too; if it doesn't, the client falls back to showing the raw key text
     * instead of crashing.
     */
    private static Component dimensionDisplayName(ResourceKey<Level> dimension) {
        String key = "dimension." + dimension.location().getNamespace() + "." + dimension.location().getPath();
        return Component.translatable(key).withStyle(ChatFormatting.LIGHT_PURPLE);
    }
}
