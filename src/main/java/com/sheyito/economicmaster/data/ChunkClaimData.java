package com.sheyito.economicmaster.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-disk shape of &lt;world&gt;/sheyitoscurrency/chunk_claim_data.json - how many chunks each
 * player has claimed so far (via FTB Chunks), per player uuid. Drives the quadratic pricing in
 * {@code ChunkClaimLogic}.
 */
public class ChunkClaimData {
    public Map<String, Integer> claimCount = new LinkedHashMap<>();

    public static ChunkClaimData empty() {
        return new ChunkClaimData();
    }
}
