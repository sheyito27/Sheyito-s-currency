package com.sheyito.economicmaster.subscription;

import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.data.PlayerSubscription;
import com.sheyito.economicmaster.data.SubscriptionData;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.GameTime;
import com.sheyito.economicmaster.util.JsonFileUtil;
import com.sheyito.economicmaster.util.Money;
import com.sheyito.economicmaster.util.TransactionSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Fully player-to-player, direct-pledge subscriptions - but never charged unilaterally: running
 * "/subscribe <jugador> <dinero> <tiempo> [descripcion]" only sends a proposal (see
 * {@link #invite}), exactly like {@code /trade}. Nothing is charged and no recurring payment is
 * created until the target player explicitly runs "/subscribe accept" ({@link #acceptInvite}),
 * which is what actually calls {@link #subscribe}. A player can hold any number of active
 * subscriptions simultaneously, both as payer ({@link #providersFor}) and as recipient
 * ({@link #clientsFor}). Billing runs off in-game days via {@link GameTime}, so offline server
 * time never counts.
 */
public class SubscriptionManager {

    private static final int INVITE_TIMEOUT_TICKS = 20 * 60;

    private static volatile SubscriptionManager instance;

    /** A not-yet-accepted proposal, keyed by the UUID of whoever would pay. */
    private record SubscriptionInvite(UUID receiverUuid, double price, int intervalGameDays, String description, long expiresAtTick) {
    }

    private final Path file;
    private final List<PlayerSubscription> subscriptions = new CopyOnWriteArrayList<>();
    private final Map<UUID, SubscriptionInvite> pendingInvites = new ConcurrentHashMap<>();
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
     * Sends {@code payerUuid} a proposal to pay {@code receiver} - nothing is charged and no
     * subscription exists until they run "/subscribe accept". A new invite to the same payer
     * overwrites whatever was pending before, same as {@code TradeManager}.
     */
    public void invite(MinecraftServer server, ServerPlayer receiver, UUID payerUuid, double price, int intervalGameDays, String description) {
        pendingInvites.put(payerUuid, new SubscriptionInvite(receiver.getUUID(), price, intervalGameDays, description, server.getTickCount() + INVITE_TIMEOUT_TICKS));

        receiver.sendSystemMessage(Component.literal("§a[Sheyito's currency] §fPropuesta de suscripcion enviada."));

        ServerPlayer payerPlayer = server.getPlayerList().getPlayer(payerUuid);
        if (payerPlayer != null) {
            Component acceptButton = Component.literal("[Aceptar]")
                    .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/subscribe accept")));
            String suffix = description.isBlank() ? "" : " (" + description + ")";
            payerPlayer.sendSystemMessage(Component.literal("§a[Sheyito's currency] §f" + receiver.getGameProfile().getName() + " te propone que le pagues " + Money.format(price) + " cada " + Math.max(1, intervalGameDays) + " dias." + suffix + " ").append(acceptButton));
        }
    }

    /**
     * {@code payer} accepts their pending proposal - this is the only place {@link #subscribe}
     * actually gets called from. Returns false if there was no pending invite, if the payer
     * can't afford it, or if whoever sent the invite is no longer online. Only a successful
     * accept consumes the invite - if it fails for insufficient funds, it stays pending so the
     * payer can top up and retry instead of having to be re-invited.
     */
    public boolean acceptInvite(MinecraftServer server, ServerPlayer payer) {
        SubscriptionInvite invite = pendingInvites.get(payer.getUUID());
        if (invite == null) {
            return false;
        }
        ServerPlayer receiver = server.getPlayerList().getPlayer(invite.receiverUuid());
        if (receiver == null) {
            payer.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fEse jugador ya no esta conectado."));
            return false;
        }

        boolean success = subscribe(server, receiver, payer.getUUID(), invite.price(), invite.intervalGameDays(), invite.description());
        if (success) {
            pendingInvites.remove(payer.getUUID());
            receiver.sendSystemMessage(Component.literal("§a[Sheyito's currency] §f" + payer.getGameProfile().getName() + " acepto tu propuesta: te paga " + Money.format(Money.round(invite.price())) + " cada " + Math.max(1, invite.intervalGameDays()) + " dias."));
        }
        return success;
    }

    /** {@code payer} declines their pending proposal. Returns false if there was none. */
    public boolean denyInvite(MinecraftServer server, ServerPlayer payer) {
        SubscriptionInvite invite = pendingInvites.remove(payer.getUUID());
        if (invite == null) {
            return false;
        }
        ServerPlayer receiver = server.getPlayerList().getPlayer(invite.receiverUuid());
        if (receiver != null) {
            receiver.sendSystemMessage(Component.literal("§c[Sheyito's currency] §f" + payer.getGameProfile().getName() + " rechazo tu propuesta de suscripcion."));
        }
        return true;
    }

    /** Called periodically by the scheduler alongside {@link #processDueCharges}. */
    public void expireInvites(MinecraftServer server) {
        long now = server.getTickCount();
        pendingInvites.entrySet().removeIf(entry -> entry.getValue().expiresAtTick() <= now);
    }

    /**
     * Actually charges the first period and creates the recurring record. Only ever called from
     * {@link #acceptInvite} - never directly from a command, so a payer's money can't move
     * without them explicitly accepting. Returns false only if the payer can't afford it (the
     * payer doesn't need to be online for this to work, e.g. when called mid-{@code accept}).
     */
    public boolean subscribe(MinecraftServer server, ServerPlayer receiver, UUID payer, double price, int intervalGameDays, String description) {
        double rounded = Money.round(price);
        if (!EconomyManager.get().take(payer, EconomyManager.get().grossWithTax(rounded))) {
            return false;
        }
        EconomyManager.get().giveEarned(receiver.getUUID(), EconomyManager.get().netAfterTax(rounded));

        int interval = Math.max(1, intervalGameDays);
        long next = GameTime.currentDay(server) + interval;
        subscriptions.add(new PlayerSubscription(payer.toString(), receiver.getUUID().toString(), rounded, interval, description, next));
        dirty.set(true);

        ServerPlayer payerPlayer = server.getPlayerList().getPlayer(payer);
        if (payerPlayer != null) {
            payerPlayer.sendSystemMessage(Component.literal("§a[Sheyito's currency] §f" + receiver.getGameProfile().getName() + " registro un acuerdo: le pagas " + Money.format(rounded) + " cada " + interval + " dias (mas IVA)."
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

            double grossFromBuyer = EconomyManager.get().grossWithTax(sub.price);
            if (!EconomyManager.get().take(buyerUuid, grossFromBuyer)) {
                sub.active = false;
                subscriptions.remove(sub);
                dirty.set(true);
                if (buyerOnline != null) {
                    buyerOnline.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fTu pago a " + sellerName + " se cancelo por fondos insuficientes."));
                    TransactionSounds.failure(buyerOnline);
                }
                continue;
            }

            double netToSeller = EconomyManager.get().netAfterTax(sub.price);
            EconomyManager.get().giveEarned(sellerUuid, netToSeller);
            sub.nextChargeGameDay = currentDay + Math.max(1, sub.intervalGameDays);
            dirty.set(true);

            if (buyerOnline != null) {
                buyerOnline.sendSystemMessage(Component.literal("§a[Sheyito's currency] §fSe renovo tu pago a " + sellerName + " por " + Money.format(grossFromBuyer) + " (IVA incluido)."));
                TransactionSounds.success(buyerOnline);
            }
            if (sellerOnline != null) {
                sellerOnline.sendSystemMessage(Component.literal("§a[Sheyito's currency] §fCobraste " + Money.format(netToSeller) + " de " + EconomyManager.get().getName(buyerUuid) + " (IVA incluido)."));
                TransactionSounds.success(sellerOnline);
            }
        }
    }
}
