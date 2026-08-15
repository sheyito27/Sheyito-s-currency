package com.sheyito.economicmaster.embargo;

import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.EmbargoConfig;
import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.data.EmbargoData;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.GameTime;
import com.sheyito.economicmaster.util.ItemStackJson;
import com.sheyito.economicmaster.util.JsonFileUtil;
import com.sheyito.economicmaster.auction.AuctionPoolManager;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reacts to a player's balance going negative - today only reachable via the admin-only
 * {@code /eco charge}, since no gameplay path causes real debt yet (that is the future "pagos
 * obligatorios" feature). Owns two pieces of state, both persisted (docs/features/patronManager.md):
 * per-player grace-period countdowns, and any embargo's community vote on which seized item goes
 * to the {@link AuctionPoolManager}.
 */
public class EmbargoManager {

    private static volatile EmbargoManager instance;

    private final Path file;
    private final Map<UUID, Integer> graceSecondsElapsed = new ConcurrentHashMap<>();
    private final Map<Long, AuctionVote> activeVotes = new ConcurrentHashMap<>();
    private final Map<UUID, List<ItemStack>> pendingReturns = new ConcurrentHashMap<>();
    private final AtomicLong nextAuctionId = new AtomicLong(1);
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private int tickAccumulator = 0;

    private EmbargoManager(Path file) {
        this.file = file;
    }

    public static void init(MinecraftServer server) {
        EmbargoManager manager = new EmbargoManager(DataPaths.dataDir(server).resolve("embargo_data.json"));
        manager.load();
        instance = manager;
    }

    public static EmbargoManager get() {
        return instance;
    }

    /** Test-support seam, not part of the mod's real lifecycle - builds an in-memory instance
     * that never touches disk. */
    public static EmbargoManager createForTesting() {
        return new EmbargoManager(Path.of("build", "test-tmp", "unused-embargo-test-file.json"));
    }

    public static void installForTesting(EmbargoManager manager) {
        instance = manager;
    }

    public static void shutdown() {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    private void load() {
        EmbargoData data = JsonFileUtil.loadOrCreate(file, EmbargoData.class, EmbargoData::empty);
        data.graceSecondsElapsed.forEach((uuid, seconds) -> graceSecondsElapsed.put(UUID.fromString(uuid), seconds));
        data.pendingReturns.forEach((uuid, items) -> {
            List<ItemStack> stacks = new ArrayList<>();
            for (var json : items) {
                stacks.add(ItemStackJson.decode(json));
            }
            pendingReturns.put(UUID.fromString(uuid), stacks);
        });
        data.activeVotes.forEach((idString, record) -> {
            List<ItemStack> items = new ArrayList<>();
            for (var json : record.items) {
                items.add(ItemStackJson.decode(json));
            }
            long id = Long.parseLong(idString);
            AuctionVote vote = new AuctionVote(id, UUID.fromString(record.victimUuid), items, record.openedGameDay);
            record.votesByVoter.forEach((voter, index) -> vote.votesByVoter.put(UUID.fromString(voter), index));
            for (int i = 0; i < items.size() && i < record.highWaterMark.size(); i++) {
                vote.highWaterMark[i] = record.highWaterMark.get(i);
                vote.reachedAtTick[i] = record.reachedAtTick.get(i);
            }
            vote.announced = true;
            activeVotes.put(id, vote);
        });
        nextAuctionId.set(Math.max(1, data.nextAuctionId));
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        EmbargoData data = new EmbargoData();
        graceSecondsElapsed.forEach((uuid, seconds) -> data.graceSecondsElapsed.put(uuid.toString(), seconds));
        pendingReturns.forEach((uuid, items) -> {
            List<com.google.gson.JsonElement> encoded = new ArrayList<>();
            for (ItemStack stack : items) {
                encoded.add(ItemStackJson.encode(stack));
            }
            data.pendingReturns.put(uuid.toString(), encoded);
        });
        activeVotes.forEach((id, vote) -> {
            EmbargoData.AuctionVoteRecord record = new EmbargoData.AuctionVoteRecord();
            record.victimUuid = vote.victimUuid.toString();
            record.openedGameDay = vote.openedGameDay;
            for (ItemStack stack : vote.items) {
                record.items.add(ItemStackJson.encode(stack));
            }
            vote.votesByVoter.forEach((voter, index) -> record.votesByVoter.put(voter.toString(), index));
            for (int i = 0; i < vote.items.size(); i++) {
                record.highWaterMark.add(vote.highWaterMark[i]);
                record.reachedAtTick.add(vote.reachedAtTick[i]);
            }
            data.activeVotes.put(String.valueOf(id), record);
        });
        data.nextAuctionId = nextAuctionId.get();
        JsonFileUtil.save(file, data);
    }

    // === Grace period ===

    /** Called from {@code EconomyManager.setBalance} when a balance crosses from >=0 to
     * negative. A no-op if that player is already in an active grace period. */
    public void onBalanceWentNegative(UUID uuid) {
        if (graceSecondsElapsed.putIfAbsent(uuid, 0) == null) {
            dirty.set(true);
        }
    }

    public boolean isInGracePeriod(UUID uuid) {
        return graceSecondsElapsed.containsKey(uuid);
    }

    /** Called every server tick by {@code EmbargoScheduler}; only does real work once every ~20
     * ticks (1 real second), so a player's countdown is paused for every tick they're offline. */
    public void tickGrace(MinecraftServer server) {
        if (graceSecondsElapsed.isEmpty()) {
            return;
        }
        tickAccumulator++;
        if (tickAccumulator < 20) {
            return;
        }
        tickAccumulator = 0;

        EmbargoConfig config = ConfigManager.embargo();
        if (!config.enabled) {
            return;
        }

        for (UUID uuid : List.copyOf(graceSecondsElapsed.keySet())) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                continue;
            }
            if (EconomyManager.get().getBalance(uuid) >= 0) {
                graceSecondsElapsed.remove(uuid);
                dirty.set(true);
                player.sendSystemMessage(Component.literal("§a[Sheyito's currency] §fSaldaste tu deuda a tiempo."));
                continue;
            }
            int elapsed = graceSecondsElapsed.merge(uuid, 1, Integer::sum);
            dirty.set(true);
            if (elapsed >= config.graceSeconds) {
                executeSeizure(player, server);
            }
        }
    }

    private void executeSeizure(ServerPlayer player, MinecraftServer server) {
        graceSecondsElapsed.remove(player.getUUID());
        List<ItemStack> seized = EmbargoSeizureLogic.collectSeizable(player, player.getInventory());
        EconomyManager.get().setBalance(player.getUUID(), 0.0);
        dirty.set(true);

        player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fSe agoto tu plazo de gracia: se incauto tu armadura, armas y herramientas, y tu saldo volvio a 0. No hay vuelta atras."));

        if (seized.isEmpty()) {
            return;
        }
        long id = nextAuctionId.getAndIncrement();
        AuctionVote vote = new AuctionVote(id, player.getUUID(), seized, GameTime.currentDay(server));
        activeVotes.put(id, vote);
        dirty.set(true);
        announceIfEligible(vote, server);
    }

    // === Voting ===

    /** The oldest active vote {@code viewer} is allowed to vote in (any vote where they aren't
     * the victim), if any. */
    public Optional<Long> openVoteFor(UUID viewer) {
        return activeVotes.values().stream()
                .filter(vote -> !vote.victimUuid.equals(viewer))
                .map(vote -> vote.id)
                .min(Long::compareTo);
    }

    public List<ItemStack> voteItems(long voteId) {
        AuctionVote vote = activeVotes.get(voteId);
        return vote == null ? List.of() : vote.items;
    }

    public boolean isVoteActive(long voteId) {
        return activeVotes.containsKey(voteId);
    }

    public Integer voteOf(long voteId, UUID viewer) {
        AuctionVote vote = activeVotes.get(voteId);
        return vote == null ? null : vote.votesByVoter.get(viewer);
    }

    public void castVote(long voteId, UUID viewer, int index, MinecraftServer server) {
        AuctionVote vote = activeVotes.get(voteId);
        if (vote == null || viewer.equals(vote.victimUuid) || index < 0 || index >= vote.items.size()) {
            return;
        }
        vote.castVote(viewer, index, server.getTickCount());
        dirty.set(true);
    }

    /** Called periodically (coarse ~30s cadence, from the main {@code EconomicMasterScheduler}) -
     * day-level precision doesn't need per-tick resolution like the grace countdown does. */
    public void tickVoteClosing(MinecraftServer server) {
        if (activeVotes.isEmpty()) {
            return;
        }
        EmbargoConfig config = ConfigManager.embargo();
        long currentDay = GameTime.currentDay(server);

        for (AuctionVote vote : List.copyOf(activeVotes.values())) {
            announceIfEligible(vote, server);
            boolean enoughVoters = vote.voterCount() >= config.minVotersToClose;
            boolean enoughTime = currentDay - vote.openedGameDay >= config.minVoteGameDays;
            if (enoughVoters && enoughTime) {
                closeVote(vote, server);
            }
        }
    }

    private void closeVote(AuctionVote vote, MinecraftServer server) {
        activeVotes.remove(vote.id);
        int winnerIndex = vote.winningIndex();
        ItemStack winner = vote.items.get(winnerIndex);
        String victimName = EconomyManager.get().getName(vote.victimUuid);
        AuctionPoolManager.get().add(winner, vote.victimUuid, victimName, GameTime.currentDay(server));

        List<ItemStack> returned = new ArrayList<>();
        for (int i = 0; i < vote.items.size(); i++) {
            if (i != winnerIndex) {
                returned.add(vote.items.get(i));
            }
        }
        returnItems(vote.victimUuid, returned, server);
        dirty.set(true);

        String message = "§6[Sheyito's currency] §fLa votacion sobre el embargo de " + victimName
                + " termino: " + winner.getHoverName().getString() + " x" + winner.getCount()
                + " pasa a la pool de subastas. El resto se devolvio.";
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal(message));
        }
    }

    private void returnItems(UUID victim, List<ItemStack> items, MinecraftServer server) {
        if (items.isEmpty()) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(victim);
        if (player != null) {
            for (ItemStack stack : items) {
                player.getInventory().placeItemBackInInventory(stack);
            }
        } else {
            pendingReturns.computeIfAbsent(victim, k -> new ArrayList<>()).addAll(items);
            dirty.set(true);
        }
    }

    /** Called from {@code ServerLifecycleHandler.onPlayerLoggedIn} - hands over any items that
     * couldn't be returned because the victim was offline when their vote closed. */
    public void deliverPendingReturns(ServerPlayer player) {
        List<ItemStack> pending = pendingReturns.remove(player.getUUID());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (ItemStack stack : pending) {
            player.getInventory().placeItemBackInInventory(stack);
        }
        dirty.set(true);
        player.sendSystemMessage(Component.literal("§a[Sheyito's currency] §fSe te devolvieron los objetos incautados que no fueron elegidos en la votacion del embargo."));
    }

    private void announceIfEligible(AuctionVote vote, MinecraftServer server) {
        if (vote.announced) {
            return;
        }
        EmbargoConfig config = ConfigManager.embargo();
        long onlineExcludingVictim = server.getPlayerList().getPlayers().stream()
                .filter(p -> !p.getUUID().equals(vote.victimUuid))
                .count();
        if (onlineExcludingVictim < config.minVotersToClose) {
            return;
        }
        vote.announced = true;
        dirty.set(true);

        String victimName = EconomyManager.get().getName(vote.victimUuid);
        Component button = Component.literal("[Votar]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/sc embargo vote")));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.getUUID().equals(vote.victimUuid)) {
                p.sendSystemMessage(Component.literal("§6[Sheyito's currency] §f" + victimName
                        + " entro en embargo - vota que objeto se subasta: ").append(button));
            }
        }
    }
}
