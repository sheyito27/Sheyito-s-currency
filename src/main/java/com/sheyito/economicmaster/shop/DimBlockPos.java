package com.sheyito.economicmaster.shop;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Map key: a block position is only meaningful together with which dimension it's in. */
public record DimBlockPos(ResourceKey<Level> dimension, BlockPos pos) {
}
