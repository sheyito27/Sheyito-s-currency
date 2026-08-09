package com.sheyito.economicmaster.subscription;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.data.PlayerSubscription;
import com.sheyito.economicmaster.data.SubscriptionData;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.GameTime;
import com.sheyito.economicmaster.util.JsonFileUtil;
import com.sheyito.economicmaster.util.Money;
import com.sheyito.economicmaster.util.TransactionSounds;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fully player-to-player subscriptions: any player can offer a paid service at their own
 * price ({@link #setOffer}), and any other player can subscribe to it ({@link #subscribe}).
 * The only server-wide setting is the billing interval (subscriptions.json). Billing runs off
 * in-game days via {@link GameTime}, so offline server time never counts.
 */
public class SubscriptionManager {

    private static volatile SubscriptionManager instance;

    private final Path file;
    private final Map<UUID, Double> offers = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSubscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private SubscriptionManager(Path file) {
        this.file = file;
    }

    public static void init(MinecraftServer server) {
        SubscriptionManager manager = new SubscriptionManager(DataPaths.dataDir(server).resolve("subscriptions_data.json"));
        manager.load();
        instance = manager;
    }

    public static SubscriptionManager get() {
        return instance;
    }

    /** Test-only: an in-memory instance that never touches disk (no load(), no save() called). */
    static SubscriptionManager createForTesting() {
        return new SubscriptionManager(Path.of("build", "test-tmp", "unused-subscriptions-test-file.json"));
    }

    public static void shutdown() {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    private void load() {
        SubscriptionData data = JsonFileUtil.loadOrCreate(file, SubscriptionData.class, SubscriptionData::empty);
        data.offers.forEach((uuid, price) -> offers.put(UUID.fromString(uuid), price));
        data.subscriptions.forEach((uuid, sub) -> subscriptions.put(UUID.fromString(uuid), sub));
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        SubscriptionData data = new SubscriptionData();
        offers.forEach((uuid, price) -> data.offers.put(uuid.toString(), price));
        subscriptions.forEach((uuid, sub) -> data.subscriptions.put(uuid.toString(), sub));
        JsonFileUtil.save(file, data);
    }

    public boolean hasOffer(UUID seller) {
        return offers.containsKey(seller);
    }

    public Double getOfferPrice(UUID seller) {
        return offers.get(seller);
    }

    public Map<UUID, Double> getOffers() {
        return Map.copyOf(offers);
    }

    public long subscriberCount(UUID seller) {
        return subscriptions.values().stream().filter(s -> s.active && seller.toString().equals(s.sellerUuid)).count();
    }

    public void setOffer(UUID seller, double price) {
        offers.put(seller, Money.round(price));
        dirty.set(true);
    }

    /** Removing an offer also cancels every active subscription pointed at it. */
    public void removeOffer(MinecraftServer server, UUID seller) {
        offers.remove(seller);
        dirty.set(true);
        for (Map.Entry<UUID, PlayerSubscription> entry : new HashMap<>(subscriptions).entrySet()) {
            if (entry.getValue().active && seller.toString().equals(entry.getValue().sellerUuid)) {
                subscriptions.remove(entry.getKey());
                ServerPlayer buyer = server.getPlayerList().getPlayer(entry.getKey());
                if (buyer != null) {
                    buyer.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fTu suscripcion a " + EconomyManager.get().getName(seller) + " fue cancelada porque dejo de ofrecer el servicio."));
                }
            }
        }
    }

    public PlayerSubscription getSubscription(UUID buyer) {
        return subscriptions.get(buyer);
    }

    /**
     * Subscribes {@code buyer} to {@code seller}'s current offer, charging the first period
     * immediately. Returns false if the seller has no offer or the buyer can't afford it.
     */
    public boolean subscribe(MinecraftServer server, ServerPlayer buyer, UUID seller) {
        Double price = offers.get(seller);
        if (price == null) {
            return false;
        }
        if (!EconomyManager.get().take(buyer.getUUID(), price)) {
            return false;
        }
        EconomyManager.get().giveEarned(seller, price);

        long next = GameTime.currentDay(server) + Math.max(1, ConfigManager.subscriptions().intervalGameDays);
        subscriptions.put(buyer.getUUID(), new PlayerSubscription(seller.toString(), price, next));
        dirty.set(true);

        ServerPlayer sellerPlayer = server.getPlayerList().getPlayer(seller);
        if (sellerPlayer != null) {
            sellerPlayer.sendSystemMessage(Component.literal("§a[Sheyito's currency] §f" + buyer.getGameProfile().getName() + " se suscribio a tu servicio por " + Money.format(price) + "."));
        }
        return true;
    }

    public boolean cancel(UUID buyer) {
        PlayerSubscription removed = subscriptions.remove(buyer);
        if (removed != null) {
            dirty.set(true);
        }
        return removed != null;
    }

    /**
     * Called periodically by the scheduler: charges every subscription whose next billing
     * day has arrived, extends it on success, or cancels it (and tells the buyer) if funds
     * are insufficient or the seller no longer has an offer.
     */
    public void processDueCharges(MinecraftServer server) {
        long currentDay = GameTime.currentDay(server);
        int intervalDays = Math.max(1, ConfigManager.subscriptions().intervalGameDays);

        for (Map.Entry<UUID, PlayerSubscription> entry : new HashMap<>(subscriptions).entrySet()) {
            PlayerSubscription sub = entry.getValue();
            if (!sub.active || sub.nextChargeGameDay > currentDay) {
                continue;
            }
            UUID buyerUuid = entry.getKey();
            UUID sellerUuid = UUID.fromString(sub.sellerUuid);
            ServerPlayer buyerOnline = server.getPlayerList().getPlayer(buyerUuid);
            ServerPlayer sellerOnline = server.getPlayerList().getPlayer(sellerUuid);
            String sellerName = EconomyManager.get().getName(sellerUuid);

            if (!offers.containsKey(sellerUuid) || !EconomyManager.get().take(buyerUuid, sub.price)) {
                subscriptions.remove(buyerUuid);
                dirty.set(true);
                if (buyerOnline != null) {
                    buyerOnline.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fTu suscripcion a " + sellerName + " se cancelo (fondos insuficientes o el servicio ya no existe)."));
                    TransactionSounds.failure(buyerOnline);
                }
                continue;
            }

            EconomyManager.get().giveEarned(sellerUuid, sub.price);
            sub.nextChargeGameDay = currentDay + intervalDays;
            dirty.set(true);

            if (buyerOnline != null) {
                buyerOnline.sendSystemMessage(Component.literal("§a[Sheyito's currency] §fSe renovo tu suscripcion a " + sellerName + " por " + Money.format(sub.price) + "."));
                TransactionSounds.success(buyerOnline);
            }
            if (sellerOnline != null) {
                sellerOnline.sendSystemMessage(Component.literal("§a[Sheyito's currency] §fCobraste " + Money.format(sub.price) + " de tu suscriptor " + EconomyManager.get().getName(buyerUuid) + "."));
                TransactionSounds.success(sellerOnline);
            }
        }
    }
}
