package com.sheyito.economicmaster.data;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * On-disk shape of &lt;world&gt;/sheyitoscurrency/chunk_claim_data.json - everything
 * {@code ChunkClaimRegistry} tracks per player uuid: how many chunks they've claimed (drives the
 * {@code n^1.5} claim pricing), how many they currently have force-loaded and when they were
 * last billed for it, and whether an auto-unload is waiting for their next login (they were
 * offline when their force-load rent went unpaid).
 */
public class ChunkClaimData {
    public Map<String, Integer> claimCount = new LinkedHashMap<>();
    public Map<String, Integer> loadedCount = new LinkedHashMap<>();
    public Map<String, Long> lastForceLoadRentDay = new LinkedHashMap<>();
    public Set<String> pendingForceUnload = new LinkedHashSet<>();

    public static ChunkClaimData empty() {
        return new ChunkClaimData();
    }
}
