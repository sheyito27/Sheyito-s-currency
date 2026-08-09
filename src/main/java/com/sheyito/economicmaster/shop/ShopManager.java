package com.sheyito.economicmaster.shop;

import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.data.ShopData;
import com.sheyito.economicmaster.data.ShopRecord;
import com.sheyito.economicmaster.util.JsonFileUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ShopManager {

    private static volatile ShopManager instance;

    private final Path file;
    private final Map<DimBlockPos, ShopSign> shopsBySign = new ConcurrentHashMap<>();
    private final Map<DimBlockPos, UUID> chestOwners = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private ShopManager(Path file) {
        this.file = file;
    }

    public static void init(MinecraftServer server) {
        ShopManager manager = new ShopManager(DataPaths.dataDir(server).resolve("shops.json"));
        manager.load();
        instance = manager;
    }

    public static ShopManager get() {
        return instance;
    }

    public static void shutdown() {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    private void load() {
        ShopData data = JsonFileUtil.loadOrCreate(file, ShopData.class, ShopData::empty);
        for (ShopRecord record : data.shops) {
            toRuntime(record).ifPresent(sign -> {
                shopsBySign.put(sign.signKey(), sign);
                chestOwners.put(sign.chestKey(), sign.ownerUuid());
            });
        }
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        ShopData data = new ShopData();
        for (ShopSign sign : shopsBySign.values()) {
            data.shops.add(toRecord(sign));
        }
        JsonFileUtil.save(file, data);
    }

    public Optional<ShopSign> getShop(ResourceKey<Level> dimension, BlockPos signPos) {
        return Optional.ofNullable(shopsBySign.get(new DimBlockPos(dimension, signPos)));
    }

    public boolean isProtectedSign(ResourceKey<Level> dimension, BlockPos pos) {
        return shopsBySign.containsKey(new DimBlockPos(dimension, pos));
    }

    public boolean isProtectedChest(ResourceKey<Level> dimension, BlockPos pos) {
        return chestOwners.containsKey(new DimBlockPos(dimension, pos));
    }

    /** A chest can have multiple signs attached to it (e.g. one SELL, one BUY). */
    public java.util.List<ShopSign> shopsForChest(ResourceKey<Level> dimension, BlockPos chestPos) {
        DimBlockPos key = new DimBlockPos(dimension, chestPos);
        return shopsBySign.values().stream().filter(s -> s.chestKey().equals(key)).toList();
    }

    public Optional<UUID> chestOwner(ResourceKey<Level> dimension, BlockPos pos) {
        return Optional.ofNullable(chestOwners.get(new DimBlockPos(dimension, pos)));
    }

    /**
     * @return empty if the sign was already valid but the backing chest belongs to someone
     * else; otherwise the sign is registered (and the chest's ownership is claimed for the
     * placer if it had none yet).
     */
    public boolean registerShop(ShopSign sign) {
        DimBlockPos chestKey = sign.chestKey();
        UUID existingOwner = chestOwners.get(chestKey);
        if (existingOwner != null && !existingOwner.equals(sign.ownerUuid())) {
            return false;
        }
        shopsBySign.put(sign.signKey(), sign);
        chestOwners.put(chestKey, sign.ownerUuid());
        dirty.set(true);
        return true;
    }

    public void removeShop(ResourceKey<Level> dimension, BlockPos signPos) {
        ShopSign removed = shopsBySign.remove(new DimBlockPos(dimension, signPos));
        if (removed == null) {
            return;
        }
        dirty.set(true);
        boolean chestStillUsed = shopsBySign.values().stream().anyMatch(s -> s.chestKey().equals(removed.chestKey()));
        if (!chestStillUsed) {
            chestOwners.remove(removed.chestKey());
        }
    }

    private static ShopRecord toRecord(ShopSign sign) {
        ShopRecord record = new ShopRecord();
        record.dimensionId = sign.dimension().location().toString();
        record.signX = sign.signPos().getX();
        record.signY = sign.signPos().getY();
        record.signZ = sign.signPos().getZ();
        record.chestX = sign.chestPos().getX();
        record.chestY = sign.chestPos().getY();
        record.chestZ = sign.chestPos().getZ();
        record.ownerUuid = sign.ownerUuid().toString();
        record.ownerName = sign.ownerName();
        record.action = sign.action().name();
        record.price = sign.price();
        record.itemId = BuiltInRegistries.ITEM.getKey(sign.item()).toString();
        record.quantity = sign.quantity();
        return record;
    }

    private static Optional<ShopSign> toRuntime(ShopRecord record) {
        ResourceLocation dimId = ResourceLocation.tryParse(record.dimensionId);
        ResourceLocation itemId = ResourceLocation.tryParse(record.itemId);
        if (dimId == null || itemId == null) {
            return Optional.empty();
        }
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(itemId);
        ShopAction action;
        try {
            action = ShopAction.valueOf(record.action);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (item.isEmpty()) {
            return Optional.empty();
        }
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimId);
        return Optional.of(new ShopSign(
                dimension,
                new BlockPos(record.signX, record.signY, record.signZ),
                new BlockPos(record.chestX, record.chestY, record.chestZ),
                UUID.fromString(record.ownerUuid),
                record.ownerName,
                action,
                record.price,
                item.get(),
                record.quantity));
    }
}
