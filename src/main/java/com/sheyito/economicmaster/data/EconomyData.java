package com.sheyito.economicmaster.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-disk shape of &lt;world&gt;/sheyitoscurrency/balances.json.
 */
public class EconomyData {
    public Map<String, Double> balances = new LinkedHashMap<>();
    public Map<String, String> names = new LinkedHashMap<>();
    public Map<String, Double> xp = new LinkedHashMap<>();

    public static EconomyData empty() {
        return new EconomyData();
    }
}
