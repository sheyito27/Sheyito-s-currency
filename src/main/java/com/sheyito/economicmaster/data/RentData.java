package com.sheyito.economicmaster.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-disk shape of &lt;world&gt;/sheyitoscurrency/rent_data.json - per player, when they were last
 * charged the progressive profit tax and what their balance was at that moment (the baseline
 * "ganancias" is measured against next time).
 */
public class RentData {

    public Map<String, RentRecord> records = new LinkedHashMap<>();

    public static RentData empty() {
        return new RentData();
    }

    public static class RentRecord {
        public long lastRentDay;
        public double balanceSnapshot;
    }
}
