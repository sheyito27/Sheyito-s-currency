package com.sheyito.economicmaster.auction;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.LiquidationConfig;
import com.sheyito.economicmaster.data.AuctionPoolData;
import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.ItemStackJson;
import com.sheyito.economicmaster.util.JsonFileUtil;
import com.sheyito.economicmaster.util.Money;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Storage for items that won a liquidation vote (see {@code LiquidationManager}) - sold off one at a
 * time through a real bidding auction on whichever item sits at the front of the queue
 * ({@link #items} index 0). Follows the manager-with-lifecycle pattern (docs/features/patronManager.md).
 *
 * <p>Only one auction is ever active - same "one at a time" spirit as the liquidation's own seizure
 * vote ({@code AuctionVote}). Nothing opens automatically: {@link #startAuction} - called from the
 * "puesto de subastas" multiblock's villager NPC ({@code AuctionStandListener}), the only way to
 * pick what goes up - is the sole way a {@link FrontAuction} ever begins, confirmed with the user
 * over the earlier "opens the moment the pool stops being empty" design. Closing an auction (sold
 * or expired unsold) never auto-opens the next one either - it just goes idle until someone visits
 * the stand again. The winning bid is taken via {@link EconomyManager#take} the instant it's
 * placed (escrowed) - refunded in full if later outbid, and simply never given to anyone if it
 * wins, which IS the burn: this mod never redistributes auction proceeds, same as every other sink
 * (IVA de transmisión, renta de force-load, ...).
 *
 * <p>Closing works exactly like the liquidation's own grace period ({@code LiquidationManager#tickGrace}):
 * a real-time, per-second countdown ({@link #tickInactivity}, driven by the dedicated
 * {@code AuctionScheduler} instead of the coarse ~30s economy scheduler) that resets to 0 on every
 * bid ({@link #placeBid}) and, once it reaches {@code LiquidationConfig.auctionInactivitySeconds}
 * without a fresh bid, closes the auction - same fixed real-time checkpoints
 * ({@link #announceCountdown}) reused from that same grace period, minus the button (that only
 * ever appears on the start/bid announcements, never on a plain time-remaining reminder).
 */
public class AuctionPoolManager {

    private static volatile AuctionPoolManager instance;

    private final Path file;
    private final List<PooledItem> items = new CopyOnWriteArrayList<>();
    private final Map<UUID, List<ItemStack>> pendingDeliveries = new ConcurrentHashMap<>();
    private FrontAuction frontAuction;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private int inactivityTickAccumulator = 0;

    public record PooledItem(ItemStack stack, UUID seizedFromUuid, String seizedFromName, long addedAtGameDay) {
    }

    /** Bidding state of whichever item is at {@code items.get(0)} - null while the pool is empty.
     * {@code highestBidder} stays null until the first bid comes in. {@code elapsedInactivitySeconds}
     * is the real-time no-bid countdown - reset to 0 by every successful bid, see {@link #tickInactivity}. */
    private static class FrontAuction {
        UUID highestBidder;
        double highestBid;
        int elapsedInactivitySeconds;
    }

    public enum BidResult {
        SUCCESS, NO_ACTIVE_AUCTION, CANNOT_BID_ON_OWN_ITEM, TOO_LOW, INSUFFICIENT_FUNDS
    }

    public enum StartResult {
        SUCCESS, ALREADY_ACTIVE, ITEM_NOT_FOUND, NOT_ENOUGH_PLAYERS
    }

    private AuctionPoolManager(Path file) {
        this.file = file;
    }

    public static void init(MinecraftServer server) {
        AuctionPoolManager manager = new AuctionPoolManager(DataPaths.dataDir(server).resolve("auction_pool_data.json"));
        manager.load();
        instance = manager;
    }

    public static AuctionPoolManager get() {
        return instance;
    }

    public static void installForTesting(AuctionPoolManager manager) {
        instance = manager;
    }

    /** Test-support seam, not part of the mod's real lifecycle - builds an in-memory instance
     * that never touches disk. */
    public static AuctionPoolManager createForTesting() {
        return new AuctionPoolManager(Path.of("build", "test-tmp", "unused-auction-pool-test-file.json"));
    }

    public static void shutdown() {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    private void load() {
        AuctionPoolData data = JsonFileUtil.loadOrCreate(file, AuctionPoolData.class, AuctionPoolData::empty);
        for (AuctionPoolData.PooledItemRecord record : data.items) {
            items.add(new PooledItem(
                    ItemStackJson.decode(record.item),
                    UUID.fromString(record.seizedFromUuid),
                    record.seizedFromName,
                    record.addedAtGameDay));
        }
        if (data.frontAuction != null) {
            FrontAuction auction = new FrontAuction();
            auction.highestBidder = data.frontAuction.highestBidderUuid == null
                    ? null : UUID.fromString(data.frontAuction.highestBidderUuid);
            auction.highestBid = data.frontAuction.highestBid;
            auction.elapsedInactivitySeconds = data.frontAuction.elapsedInactivitySeconds;
            frontAuction = auction;
        }
        data.pendingDeliveries.forEach((uuid, encoded) -> {
            List<ItemStack> stacks = new ArrayList<>();
            for (var json : encoded) {
                stacks.add(ItemStackJson.decode(json));
            }
            pendingDeliveries.put(UUID.fromString(uuid), stacks);
        });
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        AuctionPoolData data = new AuctionPoolData();
        for (PooledItem pooled : items) {
            AuctionPoolData.PooledItemRecord record = new AuctionPoolData.PooledItemRecord();
            record.item = ItemStackJson.encode(pooled.stack());
            record.seizedFromUuid = pooled.seizedFromUuid().toString();
            record.seizedFromName = pooled.seizedFromName();
            record.addedAtGameDay = pooled.addedAtGameDay();
            data.items.add(record);
        }
        if (frontAuction != null) {
            AuctionPoolData.FrontAuctionRecord record = new AuctionPoolData.FrontAuctionRecord();
            record.highestBidderUuid = frontAuction.highestBidder == null ? null : frontAuction.highestBidder.toString();
            record.highestBid = frontAuction.highestBid;
            record.elapsedInactivitySeconds = frontAuction.elapsedInactivitySeconds;
            data.frontAuction = record;
        }
        pendingDeliveries.forEach((uuid, stacks) -> {
            List<com.google.gson.JsonElement> encoded = new ArrayList<>();
            for (ItemStack stack : stacks) {
                encoded.add(ItemStackJson.encode(stack));
            }
            data.pendingDeliveries.put(uuid.toString(), encoded);
        });
        JsonFileUtil.save(file, data);
    }

    /** Called from {@code LiquidationManager.closeVote} when the community's vote picks an item to
     * auction. Just joins the pool - nothing opens on its own, see {@link #startAuction}. */
    public void add(ItemStack stack, UUID seizedFromUuid, String seizedFromName, long gameDay) {
        items.add(new PooledItem(stack.copy(), seizedFromUuid, seizedFromName, gameDay));
        dirty.set(true);
    }

    public List<PooledItem> list() {
        return List.copyOf(items);
    }

    /** The item currently up for bid, if any. */
    public Optional<PooledItem> currentAuctionItem() {
        return frontAuction == null ? Optional.empty() : Optional.of(items.get(0));
    }

    /**
     * Picks {@code chosen} to be the one up for bid right now - called from the "puesto de
     * subastas" villager's selection menu, the only way an auction ever starts. Moves it to the
     * front of the queue (everything else just shifts back, nothing is lost) and opens a fresh
     * {@link FrontAuction} on it. Rejects if an auction is already active (finish or wait for that
     * one first), if {@code chosen} somehow isn't in the pool anymore (picked from a stale menu),
     * or if fewer than {@code LiquidationConfig.minPlayersToStartAuction} players besides the item's
     * own victim are online (no point opening a bid nobody's around to place) - same counting
     * convention as the liquidation vote's {@code minVotersToClose}. On success, broadcasts the start
     * announcement with the "[Pujar]" button - see {@link #announceStart}.
     */
    public StartResult startAuction(PooledItem chosen, MinecraftServer server) {
        if (frontAuction != null) {
            return StartResult.ALREADY_ACTIVE;
        }
        if (!items.contains(chosen)) {
            return StartResult.ITEM_NOT_FOUND;
        }
        if (!enoughPlayersOnline(chosen.seizedFromUuid(), server)) {
            return StartResult.NOT_ENOUGH_PLAYERS;
        }
        items.remove(chosen);
        items.add(0, chosen);
        frontAuction = new FrontAuction();
        dirty.set(true);
        announceStart(chosen, server);
        return StartResult.SUCCESS;
    }

    private static boolean enoughPlayersOnline(UUID victim, MinecraftServer server) {
        long onlineExcludingVictim = server.getPlayerList().getPlayers().stream()
                .filter(p -> !p.getUUID().equals(victim))
                .count();
        return onlineExcludingVictim >= ConfigManager.liquidation().minPlayersToStartAuction;
    }

    public Optional<UUID> currentHighestBidder() {
        return frontAuction == null ? Optional.empty() : Optional.ofNullable(frontAuction.highestBidder);
    }

    public double currentHighestBid() {
        return frontAuction == null ? 0.0 : frontAuction.highestBid;
    }

    /**
     * Places a bid on the item currently at the front of the queue - taken (escrowed) immediately
     * via {@link EconomyManager#take}, refunded in full if later outbid. Rejects without touching
     * any money if there's nothing up for auction, the bidder is the item's own original victim,
     * the amount doesn't strictly beat the current highest bid, or the bidder can't afford it. On
     * success, resets the no-bid closing countdown back to 0 (see {@link #tickInactivity}) and
     * broadcasts the bid with the "[Pujar]" button - see {@link #announceBid}.
     */
    public BidResult placeBid(UUID bidder, double amount, MinecraftServer server) {
        if (items.isEmpty() || frontAuction == null) {
            return BidResult.NO_ACTIVE_AUCTION;
        }
        PooledItem current = items.get(0);
        if (current.seizedFromUuid().equals(bidder)) {
            return BidResult.CANNOT_BID_ON_OWN_ITEM;
        }
        if (amount <= frontAuction.highestBid) {
            return BidResult.TOO_LOW;
        }
        if (!EconomyManager.get().take(bidder, amount)) {
            return BidResult.INSUFFICIENT_FUNDS;
        }
        if (frontAuction.highestBidder != null) {
            EconomyManager.get().give(frontAuction.highestBidder, frontAuction.highestBid);
        }
        frontAuction.highestBidder = bidder;
        frontAuction.highestBid = amount;
        frontAuction.elapsedInactivitySeconds = 0;
        dirty.set(true);
        announceBid(current, bidder, amount, server);
        return BidResult.SUCCESS;
    }

    /**
     * Called every server tick by the dedicated {@code AuctionScheduler} - same per-tick,
     * throttled-to-1s pattern as {@code LiquidationManager#tickGrace}, needed for the same reason: the
     * coarse ~30s economy scheduler would only catch a no-bid timeout somewhere between 0 and 60s
     * late. A no-op the instant nothing is up for bid. Reuses that same grace-period countdown's
     * fixed real-time checkpoints ({@link #announceCountdown}) to announce how long is left before
     * the item closes unsold-or-sold-to-whoever's-winning, and closes it once {@code
     * elapsedInactivitySeconds} reaches {@code LiquidationConfig.auctionInactivitySeconds} without a
     * fresh bid resetting it (see {@link #placeBid}).
     */
    public void tickInactivity(MinecraftServer server) {
        if (frontAuction == null) {
            return;
        }
        inactivityTickAccumulator++;
        if (inactivityTickAccumulator < 20) {
            return;
        }
        inactivityTickAccumulator = 0;

        LiquidationConfig config = ConfigManager.liquidation();
        int timeout = config.auctionInactivitySeconds;
        int elapsedBefore = frontAuction.elapsedInactivitySeconds;
        announceCountdown(server, timeout - elapsedBefore, timeout);
        frontAuction.elapsedInactivitySeconds++;
        dirty.set(true);
        if (frontAuction.elapsedInactivitySeconds >= timeout) {
            closeCurrentAuction(server);
        }
    }

    private void closeCurrentAuction(MinecraftServer server) {
        PooledItem current = items.remove(0);
        UUID winner = frontAuction.highestBidder;
        double winningBid = frontAuction.highestBid;
        frontAuction = null;
        dirty.set(true);

        if (winner == null) {
            // Nobody bid at all - goes to the back of the queue for its next turn instead of
            // camping the front forever and blocking everything behind it. Stays unsold until
            // someone picks it again at the auction stand - nothing reopens on its own.
            items.add(current);
            return;
        }

        // Built BEFORE deliver() runs, on purpose: Inventory#placeItemBackInInventory mutates the
        // ItemStack in place (splits it down to empty as it inserts) - reading it for the message
        // AFTER delivering showed "Air x0" instead of the real item, same bug already fixed once
        // in LiquidationManager#closeVote.
        String winnerName = EconomyManager.get().getName(winner);
        String message = "§6[Sheyito's currency] §f" + winnerName + " se llevó "
                + current.stack().getHoverName().getString() + " x" + current.stack().getCount()
                + " de la subasta por " + Money.format(winningBid) + ".";

        deliver(winner, current.stack(), server);

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal(message));
        }
    }

    /** Broadcast when {@link #startAuction} succeeds - the "message + button" the user asked for
     * ("al comenzar una subasta debe haber un mensaje informando y un botón para acceder"). */
    private static void announceStart(PooledItem item, MinecraftServer server) {
        String message = "§6[Sheyito's currency] §f¡Empieza la subasta de "
                + item.stack().getHoverName().getString() + " x" + item.stack().getCount()
                + " (incautado a " + item.seizedFromName() + ")! ";
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal(message).append(bidButton()));
        }
    }

    /** Broadcast on every successful {@link #placeBid} - same "message + button" requirement as
     * the start announcement, so anyone can jump straight into the bidding GUI from the chat. */
    private static void announceBid(PooledItem item, UUID bidder, double amount, MinecraftServer server) {
        String bidderName = EconomyManager.get().getName(bidder);
        String message = "§6[Sheyito's currency] §f" + bidderName + " pujó " + Money.format(amount)
                + " por " + item.stack().getHoverName().getString() + " x" + item.stack().getCount() + ". ";
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal(message).append(bidButton()));
        }
    }

    /**
     * Fixed real-time checkpoints, reused as-is from {@code LiquidationManager#announceCountdown}:
     * every 10s, a dedicated warning at 10s left, and a final per-second 5..1 countdown. Skips the
     * "just started/reset" checkpoint ({@code remaining == total}) on purpose - the start/bid
     * announcement right before it already said as much. Deliberately has NO button, unlike
     * {@link #announceStart}/{@link #announceBid} - the user was explicit that only those two carry
     * one, this is just a time-remaining reminder.
     */
    private static void announceCountdown(MinecraftServer server, int remaining, int total) {
        String message;
        if (remaining == total) {
            return;
        } else if (remaining == 10) {
            message = "§e[Sheyito's currency] §fEn 10 segundos la subasta se cierra si nadie más puja.";
        } else if (remaining > 10 && remaining % 10 == 0) {
            message = "§e[Sheyito's currency] §fQuedan " + remaining + " segundos para que la subasta se cierre si nadie más puja.";
        } else if (remaining >= 1 && remaining <= 5) {
            message = "§e§l" + remaining;
        } else {
            return;
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal(message));
        }
    }

    private static Component bidButton() {
        return Component.literal("[Pujar]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/auction")));
    }

    private void deliver(UUID uuid, ItemStack stack, MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player != null) {
            player.getInventory().placeItemBackInInventory(stack);
        } else {
            pendingDeliveries.computeIfAbsent(uuid, k -> new ArrayList<>()).add(stack);
            dirty.set(true);
        }
    }

    /** Called from {@code ServerLifecycleHandler.onPlayerLoggedIn} - hands over any auction
     * prizes won while offline. */
    public void deliverPending(ServerPlayer player) {
        List<ItemStack> pending = pendingDeliveries.remove(player.getUUID());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (ItemStack stack : pending) {
            player.getInventory().placeItemBackInInventory(stack);
        }
        dirty.set(true);
        player.sendSystemMessage(Component.literal(
                "§a[Sheyito's currency] §fSe te entregó lo que ganaste en la subasta mientras estabas desconectado."));
    }

    /** Pops the oldest pooled item (FIFO), for "/sc liquidation withdraw" - an admin override
     * that works regardless of auction state. If that item had an active bidder, refunds their
     * escrowed bid first, so no money is left stranded with neither an item nor a refund. */
    public Optional<PooledItem> retrieveNext() {
        if (items.isEmpty()) {
            return Optional.empty();
        }
        PooledItem next = items.remove(0);
        if (frontAuction != null && frontAuction.highestBidder != null) {
            EconomyManager.get().give(frontAuction.highestBidder, frontAuction.highestBid);
        }
        frontAuction = null;
        dirty.set(true);
        return Optional.of(next);
    }
}
