package com.sheyito.economicmaster.auction;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.EmbargoConfig;
import com.sheyito.economicmaster.data.AuctionPoolData;
import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.GameTime;
import com.sheyito.economicmaster.util.ItemStackJson;
import com.sheyito.economicmaster.util.JsonFileUtil;
import com.sheyito.economicmaster.util.Money;
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
 * Storage for items that won an embargo vote (see {@code EmbargoManager}) - sold off one at a
 * time through a real bidding auction on whichever item sits at the front of the queue
 * ({@link #items} index 0). Follows the manager-with-lifecycle pattern (docs/features/patronManager.md).
 *
 * <p>Only one auction is ever active - same "one at a time" spirit as the embargo's own seizure
 * vote ({@code AuctionVote}). A fresh {@link FrontAuction} opens automatically the moment the pool
 * stops being empty, or the moment the previous auction resolves (sold or expired unsold) and
 * there's something left behind it. The winning bid is taken via {@link EconomyManager#take} the
 * instant it's placed (escrowed) - refunded in full if later outbid, and simply never given to
 * anyone if it wins, which IS the burn: this mod never redistributes auction proceeds, same as
 * every other sink (IVA de transmisión, renta de force-load, ...).
 */
public class AuctionPoolManager {

    private static volatile AuctionPoolManager instance;

    private final Path file;
    private final List<PooledItem> items = new CopyOnWriteArrayList<>();
    private final Map<UUID, List<ItemStack>> pendingDeliveries = new ConcurrentHashMap<>();
    private FrontAuction frontAuction;
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    public record PooledItem(ItemStack stack, UUID seizedFromUuid, String seizedFromName, long addedAtGameDay) {
    }

    /** Bidding state of whichever item is at {@code items.get(0)} - null while the pool is empty.
     * {@code highestBidder} stays null until the first bid comes in. */
    private static class FrontAuction {
        UUID highestBidder;
        double highestBid;
        long openedGameDay;
    }

    public enum BidResult {
        SUCCESS, NO_ACTIVE_AUCTION, CANNOT_BID_ON_OWN_ITEM, TOO_LOW, INSUFFICIENT_FUNDS
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
            auction.openedGameDay = data.frontAuction.openedGameDay;
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
            record.openedGameDay = frontAuction.openedGameDay;
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

    /** Called from {@code EmbargoManager.closeVote} when the community's vote picks an item to
     * auction. Opens a fresh bidding round on it immediately if the pool was empty (nothing
     * already at the front) - otherwise it just queues behind whatever's currently up for bid. */
    public void add(ItemStack stack, UUID seizedFromUuid, String seizedFromName, long gameDay) {
        boolean wasEmpty = items.isEmpty();
        items.add(new PooledItem(stack.copy(), seizedFromUuid, seizedFromName, gameDay));
        if (wasEmpty) {
            FrontAuction auction = new FrontAuction();
            auction.openedGameDay = gameDay;
            frontAuction = auction;
        }
        dirty.set(true);
    }

    public List<PooledItem> list() {
        return List.copyOf(items);
    }

    /** The item currently up for bid, if any. */
    public Optional<PooledItem> currentAuctionItem() {
        return items.isEmpty() ? Optional.empty() : Optional.of(items.get(0));
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
     * the amount doesn't strictly beat the current highest bid, or the bidder can't afford it.
     */
    public BidResult placeBid(UUID bidder, double amount) {
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
        dirty.set(true);
        return BidResult.SUCCESS;
    }

    /** Called periodically (coarse ~30s cadence, from {@code EconomicMasterScheduler}) -
     * day-level precision doesn't need per-tick resolution. */
    public void tickAuctionClosing(MinecraftServer server) {
        if (items.isEmpty() || frontAuction == null) {
            return;
        }
        EmbargoConfig config = ConfigManager.embargo();
        long currentDay = GameTime.currentDay(server);
        if (currentDay - frontAuction.openedGameDay < config.auctionDurationGameDays) {
            return;
        }
        closeCurrentAuction(server);
    }

    private void closeCurrentAuction(MinecraftServer server) {
        PooledItem current = items.remove(0);
        UUID winner = frontAuction.highestBidder;
        double winningBid = frontAuction.highestBid;
        dirty.set(true);

        if (winner == null) {
            // Nobody bid at all - goes to the back of the queue for its next turn instead of
            // camping the front forever and blocking everything behind it.
            items.add(current);
            openFrontAuction(GameTime.currentDay(server));
            return;
        }

        deliver(winner, current.stack(), server);
        openFrontAuction(GameTime.currentDay(server));

        String winnerName = EconomyManager.get().getName(winner);
        String message = "§6[Sheyito's currency] §f" + winnerName + " se llevó "
                + current.stack().getHoverName().getString() + " x" + current.stack().getCount()
                + " de la subasta por " + Money.format(winningBid) + ".";
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal(message));
        }
    }

    private void openFrontAuction(long currentDay) {
        if (items.isEmpty()) {
            frontAuction = null;
            return;
        }
        FrontAuction auction = new FrontAuction();
        auction.openedGameDay = currentDay;
        frontAuction = auction;
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
    public Optional<PooledItem> retrieveNext(MinecraftServer server) {
        if (items.isEmpty()) {
            return Optional.empty();
        }
        PooledItem next = items.remove(0);
        if (frontAuction != null && frontAuction.highestBidder != null) {
            EconomyManager.get().give(frontAuction.highestBidder, frontAuction.highestBid);
        }
        openFrontAuction(GameTime.currentDay(server));
        dirty.set(true);
        return Optional.of(next);
    }
}
