package com.sheyito.economicmaster.liquidation;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory state of one liquidation's community vote: which of the seized items gets auctioned.
 * Package-private - only {@link LiquidationManager} mutates this, everything else goes through it.
 */
class AuctionVote {

    final long id;
    final UUID victimUuid;
    final List<ItemStack> items;
    /** Parallel to {@link #items} - null entries were loose in the inventory, non-null entries
     * name the equipment slot that item was worn/held in - see {@link LiquidationSeizureLogic.SeizedItem}. */
    final EquipmentSlot[] originSlots;
    final long openedGameDay;
    final Map<UUID, Integer> votesByVoter = new LinkedHashMap<>();
    final int[] highWaterMark;
    final long[] reachedAtTick;
    boolean announced;

    AuctionVote(long id, UUID victimUuid, List<ItemStack> items, EquipmentSlot[] originSlots, long openedGameDay) {
        this.id = id;
        this.victimUuid = victimUuid;
        this.items = items;
        this.originSlots = originSlots;
        this.openedGameDay = openedGameDay;
        this.highWaterMark = new int[items.size()];
        this.reachedAtTick = new long[items.size()];
    }

    static AuctionVote fromSeizure(long id, UUID victimUuid, List<LiquidationSeizureLogic.SeizedItem> seized, long openedGameDay) {
        List<ItemStack> items = new ArrayList<>(seized.size());
        EquipmentSlot[] originSlots = new EquipmentSlot[seized.size()];
        for (int i = 0; i < seized.size(); i++) {
            items.add(seized.get(i).stack());
            originSlots[i] = seized.get(i).originSlot();
        }
        return new AuctionVote(id, victimUuid, items, originSlots, openedGameDay);
    }

    /** Casts or changes {@code voter}'s vote to {@code index}, updating the high-water mark used
     * for tie-breaking. Voting is secret and always overwrite-able. */
    void castVote(UUID voter, int index, long currentTick) {
        votesByVoter.put(voter, index);
        int newCount = countFor(index);
        if (newCount > highWaterMark[index]) {
            highWaterMark[index] = newCount;
            reachedAtTick[index] = currentTick;
        }
    }

    int countFor(int index) {
        int count = 0;
        for (int voted : votesByVoter.values()) {
            if (voted == index) {
                count++;
            }
        }
        return count;
    }

    int voterCount() {
        return votesByVoter.size();
    }

    /** Highest live vote count wins; a tie is broken by whichever candidate reached that count
     * first ({@link #reachedAtTick}), per the spec ("gana el objeto que alcanzo esa cantidad de
     * votos primero"). */
    int winningIndex() {
        int best = 0;
        for (int i = 1; i < items.size(); i++) {
            int bestCount = countFor(best);
            int count = countFor(i);
            if (count > bestCount || (count == bestCount && reachedAtTick[i] < reachedAtTick[best])) {
                best = i;
            }
        }
        return best;
    }
}
