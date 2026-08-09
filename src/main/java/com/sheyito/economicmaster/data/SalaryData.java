package com.sheyito.economicmaster.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-disk shape of &lt;world&gt;/sheyitoscurrency/salary_data.json - last salary payout,
 * as an in-game day number (see {@link com.sheyito.economicmaster.util.GameTime}), per player uuid.
 */
public class SalaryData {
    public Map<String, Long> lastPayout = new LinkedHashMap<>();

    public static SalaryData empty() {
        return new SalaryData();
    }
}
