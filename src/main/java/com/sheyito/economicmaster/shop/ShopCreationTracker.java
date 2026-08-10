package com.sheyito.economicmaster.shop;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.util.TransactionSounds;
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
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge has no "sign text finished editing" event, so this polls instead: when a player
 * places a plain sign, or right-clicks an existing one they're allowed to edit (see
 * {@link #onRightClickSign}), the position is remembered here; every tick we check
 * {@link SignBlockEntity#getPlayerWhoMayEdit()} (a vanilla field that clears itself once the
 * player finishes writing, walks away, or disconnects) and only (re)parse the sign as a
 * possible shop once that happens - this is what lets fixing a typo, or an owner updating
 * their shop's price later, actually take effect instead of only the very first edit ever
 * counting.
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

    /**
     * Re-opening an unwaxed sign to edit it later - to fix a typo on a sign that never became
     * a shop, or for the owner to update an existing shop's price/item - doesn't fire
     * {@link BlockEvent.EntityPlaceEvent} again, so it needs its own trigger. Runs regardless
     * of whether {@link ShopTradeListener} already canceled this same event for a non-owner
     * trade click; ownership is re-derived independently here from {@link ShopManager} so the
     * outcome never depends on listener order.
     */
    @SubscribeEvent
    public void onRightClickSign(PlayerInteractEvent.RightClickBlock event) {
        LevelAccessor levelAccessor = event.getLevel();
        if (!(levelAccessor instanceof Level level) || level.isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof SignBlockEntity) || blockEntity.getType() != BlockEntityType.SIGN) {
            return;
        }
        var existing = ShopManager.get().getShop(level.dimension(), pos);
        if (existing.isPresent() && !existing.get().ownerUuid().equals(player.getUUID())) {
            // Someone else's shop: ShopTradeListener treats this as a trade click, not an edit.
            return;
        }
        pending.put(pos.immutable(), new Pending(player.getUUID(), player.getGameProfile().getName(), level.dimension(), level.getGameTime()));
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
                    colorizeShopLines(sign, parsed);
                    updateStatusLine(sign, "Stock: " + ShopContainers.countMatching(ShopContainers.resolveContainer(level, chestPos), parsed.item()));
                    if (placer != null) {
                        TransactionSounds.shopCreated(placer);
                    }
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

    /** Recolors the action/price line and the quantity/item line so a shop sign reads as one at a glance. */
    private void colorizeShopLines(SignBlockEntity sign, ParsedShopText parsed) {
        String actionColor = parsed.action() == ShopAction.SELL ? "§a" : "§6";
        String priceText = formatPrice(parsed.price());
        Component actionLine = Component.literal(actionColor + parsed.action().name() + " §e" + priceText);
        Component itemLine = Component.literal("§7" + parsed.quantity() + " §b" + parsed.item().getDescription().getString());

        sign.setText(sign.getFrontText()
                .setMessage(1, actionLine)
                .setMessage(2, itemLine), true);
    }

    private static String formatPrice(double price) {
        return price == Math.floor(price) ? String.valueOf((long) price) : String.valueOf(price);
    }
}
