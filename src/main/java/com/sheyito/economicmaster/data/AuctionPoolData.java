package com.sheyito.economicmaster.data;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/**
 * On-disk shape of &lt;world&gt;/sheyitoscurrency/auction_pool_data.json - the items that won an
 * embargo vote, waiting for an admin to run "/sc embargo retirar" and actually hand them to a
 * player. Nothing here is automatic; the pool is purely storage.
 */
public class AuctionPoolData {

    public List<PooledItemRecord> items = new ArrayList<>();

    public static AuctionPoolData empty() {
        return new AuctionPoolData();
    }

    public static class PooledItemRecord {
        public JsonElement item;
        public String seizedFromUuid;
        public String seizedFromName;
        public long addedAtGameDay;
    }
}
