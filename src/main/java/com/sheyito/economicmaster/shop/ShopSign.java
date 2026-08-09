package com.sheyito.economicmaster.shop;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** Runtime (in-memory) representation of a parsed, registered shop sign. */
public record ShopSign(
        ResourceKey<Level> dimension,
        BlockPos signPos,
        BlockPos chestPos,
        UUID ownerUuid,
        String ownerName,
        ShopAction action,
        double price,
        Item item,
        int quantity
) {
    public DimBlockPos signKey() {
        return new DimBlockPos(dimension, signPos);
    }

    public DimBlockPos chestKey() {
        return new DimBlockPos(dimension, chestPos);
    }
}
