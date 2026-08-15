package com.sheyito.economicmaster.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * On-disk shape of &lt;world&gt;/sheyitoscurrency/dimension_unlocks.json - per player uuid, the
 * set of dimension ids (e.g. "minecraft:the_nether") that player has paid to unlock.
 */
public class DimensionUnlockData {
    public Map<String, Set<String>> unlockedDimensions = new LinkedHashMap<>();

    public static DimensionUnlockData empty() {
        return new DimensionUnlockData();
    }
}
