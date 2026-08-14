package com.sheyito.economicmaster.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-disk shape of &lt;world&gt;/sheyitoscurrency/debt_data.json - the in-game day (see
 * {@link com.sheyito.economicmaster.util.GameTime}) by which each indebted player's balance
 * must return to non-negative, per player uuid. The debt amount itself is not duplicated here -
 * it's read live from the player's (possibly negative) balance in {@code EconomyManager}.
 */
public class DebtData {
    public Map<String, Long> dueDay = new LinkedHashMap<>();

    public static DebtData empty() {
        return new DebtData();
    }
}
