package com.sheyito.economicmaster.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-disk shape of &lt;world&gt;/sheyitoscurrency/rent_data.json - per player, when they were last
 * charged the progressive profit tax and how much they've earned (gross, via
 * {@code EconomyManager#give}) since then. {@code lastRentDay} of -1 means "never checkpointed
 * yet" - seeded on the next periodic pass, not at accumulation time.
 */
public class RentData {

    public Map<String, RentRecord> records = new LinkedHashMap<>();

    public static RentData empty() {
        return new RentData();
    }

    public static class RentRecord {
        public long lastRentDay = -1;
        public double accumulatedGains;
    }
}
