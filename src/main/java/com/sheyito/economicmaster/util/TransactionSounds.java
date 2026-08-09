package com.sheyito.economicmaster.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/** Shared success/failure feedback sounds for every economic transaction in the mod. */
public final class TransactionSounds {

    private TransactionSounds() {
    }

    public static void success(ServerPlayer player) {
        player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.MASTER, 1.0f, 1.0f);
    }

    public static void failure(ServerPlayer player) {
        player.playNotifySound(SoundEvents.ANVIL_LAND, SoundSource.MASTER, 0.6f, 1.0f);
    }
}
