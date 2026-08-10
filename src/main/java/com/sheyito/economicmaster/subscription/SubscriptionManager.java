package com.sheyito.economicmaster.subscription;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Fully player-to-player, direct-pledge subscriptions: any player can start paying another
 * player a chosen amount every intervalGameDays (subscriptions.json) via {@link #subscribe},
 * no advance "offer" step required. A player can hold any number of these simultaneously, both
 * as payer ({@link #providersFor}) and as recipient ({@link #clientsFor}). Billing runs off
 * in-game days via {@link GameTime}, so offline server time never counts.
 */
public class SubscriptionManager {

    private static volatile SubscriptionManager instance;

    private final Path file;
    private final List<PlayerSubscription> subscriptions = new CopyOnWriteArrayList<>();
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
        subscriptions.addAll(data.subscriptions);
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        SubscriptionData data = new SubscriptionData();
        data.subscriptions.addAll(subscriptions);
        JsonFileUtil.save(file, data);
    }

    /** Every active subscription where {@code buyer} is the one paying, in creation order. */
    public List<PlayerSubscription> providersFor(UUID buyer) {
        return subscriptions.stream()
                .filter(s -> s.active && buyer.toString().equals(s.buyerUuid))
                .collect(Collectors.toList());
    }

    /** Every active subscription where {@code seller} is the one being paid, in creation order. */
    public List<PlayerSubscription> clientsFor(UUID seller) {
        return subscriptions.stream()
                .filter(s -> s.active && seller.toString().equals(s.sellerUuid))
                .collect(Collectors.toList());
    }

    /**
     * Offers a new direct pledge from {@code buyer} to {@code seller}, charging the first
     * period immediately and renewing every {@code intervalGameDays} in-game days from then on.
     * Returns false only if the buyer can't afford it.
     */
    public boolean subscribe(MinecraftServer server, ServerPlayer buyer, UUID seller, double price, int intervalGameDays, String description) {
        double rounded = Money.round(price);
        if (!EconomyManager.get().take(buyer.getUUID(), rounded)) {
            return false;
        }
        EconomyManager.get().giveEarned(seller, rounded);

        int interval = Math.max(1, intervalGameDays);
        long next = GameTime.currentDay(server) + interval;
        subscriptions.add(new PlayerSubscription(buyer.getUUID().toString(), seller.toString(), rounded, interval, description, next));
        dirty.set(true);

        ServerPlayer sellerPlayer = server.getPlayerList().getPlayer(seller);
        if (sellerPlayer != null) {
            sellerPlayer.sendSystemMessage(Component.literal("§a[Sheyito's currency] §f" + buyer.getGameProfile().getName() + " te ofrecio una suscripcion de " + Money.format(rounded) + " cada " + interval + " dias."
                    + (description.isBlank() ? "" : " (" + description + ")")));
        }
        return true;
    }

    /**
     * Cancels the buyer's Nth (1-based) outgoing subscription, using the same ordering as
     * {@link #providersFor(UUID)}. Returns false if the index is out of range.
     */
    public boolean cancelByIndex(UUID buyer, int number) {
        List<PlayerSubscription> mine = providersFor(buyer);
        if (number < 1 || number > mine.size()) {
            return false;
        }
        PlayerSubscription target = mine.get(number - 1);
        target.active = false;
        subscriptions.remove(target);
        dirty.set(true);
        return true;
    }

    /**
     * Called periodically by the scheduler: charges every subscription whose next billing
     * day has arrived, extends it on success, or cancels it (and tells the buyer) if funds
     * are insufficient.
     */
    public void processDueCharges(MinecraftServer server) {
        long currentDay = GameTime.currentDay(server);

        for (PlayerSubscription sub : List.copyOf(subscriptions)) {
            if (!sub.active || sub.nextChargeGameDay > currentDay) {
                continue;
            }
            UUID buyerUuid = UUID.fromString(sub.buyerUuid);
            UUID sellerUuid = UUID.fromString(sub.sellerUuid);
            ServerPlayer buyerOnline = server.getPlayerList().getPlayer(buyerUuid);
            ServerPlayer sellerOnline = server.getPlayerList().getPlayer(sellerUuid);
            String sellerName = EconomyManager.get().getName(sellerUuid);

            if (!EconomyManager.get().take(buyerUuid, sub.price)) {
                sub.active = false;
                subscriptions.remove(sub);
                dirty.set(true);
                if (buyerOnline != null) {
                    buyerOnline.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fTu pago a " + sellerName + " se cancelo por fondos insuficientes."));
                    TransactionSounds.failure(buyerOnline);
                }
                continue;
            }

            EconomyManager.get().giveEarned(sellerUuid, sub.price);
            sub.nextChargeGameDay = currentDay + Math.max(1, sub.intervalGameDays);
            dirty.set(true);

            if (buyerOnline != null) {
                buyerOnline.sendSystemMessage(Component.literal("§a[Sheyito's currency] §fSe renovo tu pago a " + sellerName + " por " + Money.format(sub.price) + "."));
                TransactionSounds.success(buyerOnline);
            }
            if (sellerOnline != null) {
                sellerOnline.sendSystemMessage(Component.literal("§a[Sheyito's currency] §fCobraste " + Money.format(sub.price) + " de " + EconomyManager.get().getName(buyerUuid) + "."));
                TransactionSounds.success(sellerOnline);
            }
        }
    }
}
