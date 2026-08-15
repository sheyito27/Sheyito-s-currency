package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/chunk_claim.json. Reclaiming a chunk with the
 * optional FTB Chunks mod costs Sheyicoins - if the player can't afford it, the claim is
 * blocked. This mod does not implement chunk claiming/protection itself, only the charge;
 * FTB Chunks owns everything else.
 *
 * <p>Unlike every other charge in this mod, the price is not configurable: it scales
 * quadratically per player ({@code 1000 * n} squared, where {@code n} is the 1-based number of
 * the chunk being claimed - see {@code ChunkClaimLogic}), deliberately hardcoded so server
 * owners can't flatten the anti-hoarding curve. Only the on/off switch is exposed here.
 */
public class ChunkClaimConfig {
    public boolean enabled = true;

    public static ChunkClaimConfig defaults() {
        return new ChunkClaimConfig();
    }
}
