package com.sheyito.economicmaster.data;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-disk shape of &lt;world&gt;/sheyitoscurrency/auction_pool_data.json - the items that won an
 * liquidation vote, plus the bidding auction state of whichever one is at the front of the queue
 * ({@link #frontAuction}, null while the pool is empty) and any auction prizes still waiting for
 * an offline winner to log back in ({@link #pendingDeliveries}).
 */
public class AuctionPoolData {

    public List<PooledItemRecord> items = new ArrayList<>();
    public FrontAuctionRecord frontAuction;
    public Map<String, List<JsonElement>> pendingDeliveries = new LinkedHashMap<>();

    public static AuctionPoolData empty() {
        return new AuctionPoolData();
    }

    public static class PooledItemRecord {
        public JsonElement item;
        public String seizedFromUuid;
        public String seizedFromName;
        public long addedAtGameDay;
    }

    public static class FrontAuctionRecord {
        /** Null while nobody has bid yet on the item currently at the front of the queue. */
        public String highestBidderUuid;
        public double highestBid;
        public int elapsedInactivitySeconds;
    }
}
