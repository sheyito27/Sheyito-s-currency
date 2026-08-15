package com.sheyito.economicmaster.embargo;

import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-memory state of one embargo's community vote: which of the seized items gets auctioned.
 * Package-private - only {@link EmbargoManager} mutates this, everything else goes through it.
 */
class AuctionVote {

    final long id;
    final UUID victimUuid;
    final List<ItemStack> items;
    final long openedGameDay;
    final Map<UUID, Integer> votesByVoter = new LinkedHashMap<>();
    final int[] highWaterMark;
    final long[] reachedAtTick;
    boolean announced;

    AuctionVote(long id, UUID victimUuid, List<ItemStack> items, long openedGameDay) {
        this.id = id;
        this.victimUuid = victimUuid;
        this.items = items;
        this.openedGameDay = openedGameDay;
        this.highWaterMark = new int[items.size()];
        this.reachedAtTick = new long[items.size()];
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
