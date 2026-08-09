package com.sheyito.economicmaster.shop;

import com.sheyito.economicmaster.config.ConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge has no "sign text finished editing" event, so this polls instead: when a player
 * places a plain sign, it's remembered here; every tick we check {@link SignBlockEntity#getPlayerWhoMayEdit()}
 * (a vanilla field that clears itself once the player finishes writing, walks away, or
 * disconnects) and only parse the sign as a possible shop once that happens.
 */
public class ShopCreationTracker {

    private record Pending(UUID placerUuid, String placerName, ResourceKey<Level> dimension, long placedAtTick) {
    }

    private final Map<BlockPos, Pending> pending = new ConcurrentHashMap<>();

    @SubscribeEvent
    public void onSignPlaced(BlockEvent.EntityPlaceEvent event) {
        LevelAccessor levelAccessor = event.getLevel();
        if (!(levelAccessor instanceof Level level) || level.isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer placer)) {
            return;
        }
        BlockEntity blockEntity = level.getBlockEntity(event.getPos());
        if (!(blockEntity instanceof SignBlockEntity) || blockEntity.getType() != BlockEntityType.SIGN) {
            return;
        }
        pending.put(event.getPos().immutable(), new Pending(placer.getUUID(), placer.getGameProfile().getName(), level.dimension(), level.getGameTime()));
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (pending.isEmpty()) {
            return;
        }
        int timeoutTicks = ConfigManager.shop().pendingSignTimeoutTicks;
        long now = event.getServer().overworld().getGameTime();

        pending.entrySet().removeIf(entry -> {
            BlockPos pos = entry.getKey();
            Pending info = entry.getValue();
            Level level = event.getServer().getLevel(info.dimension());
            if (level == null) {
                return true;
            }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (!(blockEntity instanceof SignBlockEntity sign)) {
                return true;
            }
            boolean timedOut = now - info.placedAtTick() > timeoutTicks;
            if (sign.getPlayerWhoMayEdit() != null && !timedOut) {
                return false;
            }

            tryRegister(sign, info, level, pos);
            return true;
        });
    }

    private void tryRegister(SignBlockEntity sign, Pending info, Level level, BlockPos signPos) {
        ShopSignParser.parse(sign).ifPresent(parsed -> {
            if (!parsed.ownerNameOnSign().equalsIgnoreCase(info.placerName())) {
                return;
            }
            ShopContainers.findAdjacentChest(level, signPos).ifPresentOrElse(chestPos -> {
                ShopSign shopSign = new ShopSign(info.dimension(), signPos.immutable(), chestPos.immutable(),
                        info.placerUuid(), info.placerName(), parsed.action(), parsed.price(), parsed.item(), parsed.quantity());

                boolean registered = ShopManager.get().registerShop(shopSign);
                ServerPlayer placer = level.getServer().getPlayerList().getPlayer(info.placerUuid());
                if (placer != null) {
                    placer.sendSystemMessage(Component.literal(registered
                            ? "§a[Sheyito's currency] §fTienda creada."
                            : "§c[Sheyito's currency] §fEse cofre ya pertenece a otro jugador - el cartel no se activo como tienda."));
                }
                if (registered) {
                    updateStatusLine(sign, "Stock: " + ShopContainers.countMatching(ShopContainers.resolveContainer(level, chestPos), parsed.item()));
                }
            }, () -> {
                ServerPlayer placer = level.getServer().getPlayerList().getPlayer(info.placerUuid());
                if (placer != null) {
                    placer.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fNecesitas exactamente un cofre pegado al cartel para crear una tienda."));
                }
            });
        });
    }

    private void updateStatusLine(SignBlockEntity sign, String status) {
        sign.setText(sign.getFrontText().setMessage(3, Component.literal(status)), true);
    }
}
