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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final Map<UUID, List<EmbargoSeizureLogic.SeizedItem>> pendingReturns = new ConcurrentHashMap<>();
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
        data.pendingReturns.forEach((uuid, records) -> {
            List<EmbargoSeizureLogic.SeizedItem> items = new ArrayList<>();
            for (EmbargoData.PendingItemRecord record : records) {
                items.add(new EmbargoSeizureLogic.SeizedItem(ItemStackJson.decode(record.item), decodeSlot(record.slot)));
            }
            pendingReturns.put(UUID.fromString(uuid), items);
        });
        data.activeVotes.forEach((idString, record) -> {
            List<ItemStack> items = new ArrayList<>();
            for (var json : record.items) {
                items.add(ItemStackJson.decode(json));
            }
            EquipmentSlot[] originSlots = new EquipmentSlot[items.size()];
            for (int i = 0; i < items.size() && i < record.originSlots.size(); i++) {
                originSlots[i] = decodeSlot(record.originSlots.get(i));
            }
            long id = Long.parseLong(idString);
            AuctionVote vote = new AuctionVote(id, UUID.fromString(record.victimUuid), items, originSlots, record.openedGameDay);
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

    private static EquipmentSlot decodeSlot(String name) {
        return name == null ? null : EquipmentSlot.valueOf(name);
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
            List<EmbargoData.PendingItemRecord> encoded = new ArrayList<>();
            for (EmbargoSeizureLogic.SeizedItem item : items) {
                EmbargoData.PendingItemRecord record = new EmbargoData.PendingItemRecord();
                record.item = ItemStackJson.encode(item.stack());
                record.slot = encodeSlot(item.originSlot());
                encoded.add(record);
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
            for (EquipmentSlot slot : vote.originSlots) {
                record.originSlots.add(encodeSlot(slot));
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

    private static String encodeSlot(EquipmentSlot slot) {
        return slot == null ? null : slot.name();
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
            int elapsedBefore = graceSecondsElapsed.get(uuid);
            announceCountdown(player, config.graceSeconds - elapsedBefore, config.graceSeconds);
            int elapsed = graceSecondsElapsed.merge(uuid, 1, Integer::sum);
            dirty.set(true);
            if (elapsed >= config.graceSeconds) {
                executeSeizure(player, server);
            }
        }
    }

    /** Fixed real-time checkpoints instead of a silent countdown: the full window right when the
     * grace period starts, every 10s after that, a dedicated warning at 10s left, and a final
     * per-second 5..1 countdown - so the threat of the seizure is actually felt, not just logged
     * somewhere the player has to check. */
    private void announceCountdown(ServerPlayer player, int remaining, int graceSeconds) {
        if (remaining == graceSeconds) {
            player.sendSystemMessage(Component.literal("§c§l[Sheyito's currency] §r§cEstás en banca rota. Tienes "
                    + remaining + " segundos para saldar tu deuda."));
        } else if (remaining == 10) {
            player.sendSystemMessage(Component.literal(
                    "§c[Sheyito's currency] §fEn 10 segundos el estado embargará tus objetos más valiosos."));
        } else if (remaining > 10 && remaining % 10 == 0) {
            player.sendSystemMessage(Component.literal(
                    "§c[Sheyito's currency] §fTe quedan " + remaining + " segundos para saldar tu deuda."));
        } else if (remaining >= 1 && remaining <= 5) {
            player.sendSystemMessage(Component.literal("§c§l" + remaining));
        }
    }

    private void executeSeizure(ServerPlayer player, MinecraftServer server) {
        graceSecondsElapsed.remove(player.getUUID());
        List<EmbargoSeizureLogic.SeizedItem> seized = EmbargoSeizureLogic.collectSeizable(player, player.getInventory());
        EconomyManager.get().setBalance(player.getUUID(), 0.0);
        dirty.set(true);

        if (seized.isEmpty()) {
            player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fNo tenías nada incautable (armadura/armas/herramientas) encima. Tu saldo volvió a 0. No hay vuelta atrás."));
            return;
        }
        player.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fSe te incautó "
                + describeItems(stacksOf(seized)) + ", y tu saldo volvió a 0. No hay vuelta atrás."));
        player.sendSystemMessage(Component.literal("§7[Sheyito's currency] Quien avisa no es traidor. Más suerte para la próxima."));

        long id = nextAuctionId.getAndIncrement();
        AuctionVote vote = AuctionVote.fromSeizure(id, player.getUUID(), seized, GameTime.currentDay(server));
        activeVotes.put(id, vote);
        dirty.set(true);
        announceIfEligible(vote, server);
    }

    private static List<ItemStack> stacksOf(List<EmbargoSeizureLogic.SeizedItem> items) {
        List<ItemStack> stacks = new ArrayList<>(items.size());
        for (EmbargoSeizureLogic.SeizedItem item : items) {
            stacks.add(item.stack());
        }
        return stacks;
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

    /**
     * Admin-only testing tool for {@code /sc liquidation close} - force-closes {@code victim}'s
     * oldest active vote right now, ignoring both {@code minVotersToClose} and
     * {@code minVoteGameDays}. Needed because {@link #tickVoteClosing}'s day requirement is
     * measured in real server uptime (see {@link com.sheyito.economicmaster.util.GameTime}), so a
     * short dev session may never accumulate enough of it no matter how many people vote.
     * A no-op (returns false) if {@code victim} has no active vote at all.
     */
    public boolean forceCloseOldestVote(UUID victim, MinecraftServer server) {
        AuctionVote vote = activeVotes.values().stream()
                .filter(v -> v.victimUuid.equals(victim))
                .min(Comparator.comparingLong(v -> v.id))
                .orElse(null);
        if (vote == null) {
            return false;
        }
        closeVote(vote, server);
        return true;
    }

    private void closeVote(AuctionVote vote, MinecraftServer server) {
        activeVotes.remove(vote.id);
        int winnerIndex = vote.winningIndex();
        ItemStack winner = vote.items.get(winnerIndex);
        String victimName = EconomyManager.get().getName(vote.victimUuid);
        AuctionPoolManager.get().add(winner, vote.victimUuid, victimName, GameTime.currentDay(server));

        List<EmbargoSeizureLogic.SeizedItem> returned = new ArrayList<>();
        for (int i = 0; i < vote.items.size(); i++) {
            if (i != winnerIndex) {
                returned.add(new EmbargoSeizureLogic.SeizedItem(vote.items.get(i), vote.originSlots[i]));
            }
        }

        // Built BEFORE returnItems() runs, on purpose: Inventory#placeItemBackInInventory
        // mutates each ItemStack in place (splits it down to empty as it inserts), so reading
        // these same stacks for the message AFTER returning them showed "Air x0" instead of the
        // real item - the item was already gone from the stack object by the time we described it.
        String message = "§6[Sheyito's currency] §fLa votación sobre el embargo de " + victimName
                + " terminó: " + winner.getHoverName().getString() + " x" + winner.getCount()
                + " pasa a la pool de subastas."
                + (returned.isEmpty() ? " Era el único objeto incautado." : " El resto (" + describeItems(stacksOf(returned)) + ") se devolvió.");

        returnItems(vote.victimUuid, returned, server);
        dirty.set(true);

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.sendSystemMessage(Component.literal(message));
        }
    }

    /** Human-readable "Nombre x1, Otro x2" list, used so seizure/return messages say exactly what
     * happened instead of a generic "armadura, armas y herramientas" that may not match reality
     * (e.g. a victim only carrying a single sword at the moment their grace period expired). */
    private static String describeItems(List<ItemStack> items) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            ItemStack stack = items.get(i);
            builder.append(stack.getHoverName().getString()).append(" x").append(stack.getCount());
        }
        return builder.toString();
    }

    private void returnItems(UUID victim, List<EmbargoSeizureLogic.SeizedItem> items, MinecraftServer server) {
        if (items.isEmpty()) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(victim);
        if (player != null) {
            for (EmbargoSeizureLogic.SeizedItem item : items) {
                giveBack(player, item);
            }
        } else {
            pendingReturns.computeIfAbsent(victim, k -> new ArrayList<>()).addAll(items);
            dirty.set(true);
        }
    }

    /** Puts a returned item back where it came from: re-equips it if it was worn/held AND that
     * slot is still free (never yanks something the player has since put on to replace it), falls
     * back to a loose inventory stack otherwise - same as any item that was never equipped. */
    private static void giveBack(ServerPlayer player, EmbargoSeizureLogic.SeizedItem item) {
        EquipmentSlot slot = item.originSlot();
        if (slot != null && player.getItemBySlot(slot).isEmpty()) {
            player.setItemSlot(slot, item.stack());
        } else {
            player.getInventory().placeItemBackInInventory(item.stack());
        }
    }

    /** Called from {@code ServerLifecycleHandler.onPlayerLoggedIn} - hands over any items that
     * couldn't be returned because the victim was offline when their vote closed. */
    public void deliverPendingReturns(ServerPlayer player) {
        List<EmbargoSeizureLogic.SeizedItem> pending = pendingReturns.remove(player.getUUID());
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (EmbargoSeizureLogic.SeizedItem item : pending) {
            giveBack(player, item);
        }
        dirty.set(true);
        player.sendSystemMessage(Component.literal("§a[Sheyito's currency] §fSe te devolvieron los objetos incautados que no fueron elegidos en la votación del embargo."));
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
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/liquidation vote")));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!p.getUUID().equals(vote.victimUuid)) {
                p.sendSystemMessage(Component.literal("§6[Sheyito's currency] §f" + victimName
                        + " entró en embargo - vota qué objeto se subasta: ").append(button));
            }
        }
    }
}
