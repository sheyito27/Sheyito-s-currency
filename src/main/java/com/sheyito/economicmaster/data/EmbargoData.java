package com.sheyito.economicmaster.data;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-disk shape of &lt;world&gt;/sheyitoscurrency/embargo_data.json. Gson never sees a UUID or
 * an ItemStack directly here - UUIDs are plain String keys (converted in EmbargoManager's
 * load()/save(), same convention as every other manager) and items go through
 * {@link com.sheyito.economicmaster.util.ItemStackJson} so their real NBT round-trips.
 */
public class EmbargoData {

    /** Player uuid -> real seconds elapsed in their current grace period (absent = not in grace). */
    public Map<String, Integer> graceSecondsElapsed = new LinkedHashMap<>();

    /** Auction id (as string) -> vote in progress. */
    public Map<String, AuctionVoteRecord> activeVotes = new LinkedHashMap<>();

    /** Victim uuid -> seized items still waiting to be handed back (they were offline when their vote closed). */
    public Map<String, List<PendingItemRecord>> pendingReturns = new LinkedHashMap<>();

    public long nextAuctionId = 1;

    public static EmbargoData empty() {
        return new EmbargoData();
    }

    public static class AuctionVoteRecord {
        public String victimUuid;
        /** Seized candidates up for vote - index in this list is the "candidate index" used everywhere else. */
        public List<JsonElement> items = new ArrayList<>();
        /** Parallel to items: which equipment slot each candidate was worn/held in (HEAD, MAINHAND,
         * ...), or null if it came loose from the main inventory - so a candidate that doesn't win
         * the vote can be re-equipped instead of just dumped back into the backpack. */
        public List<String> originSlots = new ArrayList<>();
        /** Voter uuid -> candidate index they currently back. Changing a vote overwrites the entry. */
        public Map<String, Integer> votesByVoter = new LinkedHashMap<>();
        public long openedGameDay;
        /** Parallel to items: the highest vote count each candidate has ever reached. */
        public List<Integer> highWaterMark = new ArrayList<>();
        /** Parallel to items: the server tick at which that high-water mark was reached (tie-break). */
        public List<Long> reachedAtTick = new ArrayList<>();
    }

    public static class PendingItemRecord {
        public JsonElement item;
        /** Same meaning as {@link AuctionVoteRecord#originSlots}' entries - null if it was loose. */
        public String slot;
    }
}
