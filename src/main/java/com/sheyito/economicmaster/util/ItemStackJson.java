package com.sheyito.economicmaster.util;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.item.ItemStack;

/**
 * Bridges {@link ItemStack}'s own vanilla {@code CODEC} (which correctly round-trips
 * enchantments, durability, custom names and every other data component) to the plain
 * {@code com.google.gson.JsonElement} tree {@link JsonFileUtil}'s Gson instance already reads
 * and writes natively - no custom Gson TypeAdapter needed, since Mojang's {@link JsonOps}
 * operates on that exact same JsonElement type. Used to persist seized/pooled items without
 * losing their real NBT state.
 */
public final class ItemStackJson {

    private ItemStackJson() {
    }

    public static JsonElement encode(ItemStack stack) {
        return ItemStack.CODEC.encodeStart(JsonOps.INSTANCE, stack)
                .getOrThrow(error -> new IllegalStateException("No se pudo serializar ItemStack: " + error));
    }

    public static ItemStack decode(JsonElement json) {
        return ItemStack.CODEC.parse(JsonOps.INSTANCE, json)
                .getOrThrow(error -> new IllegalStateException("No se pudo deserializar ItemStack: " + error));
    }
}
