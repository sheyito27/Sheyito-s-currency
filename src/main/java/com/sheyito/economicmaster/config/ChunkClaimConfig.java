package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/chunk_claim.json. Reclaiming a chunk with the
 * optional FTB Chunks mod costs {@link #cost} Sheyicoins, paid once per chunk - if the player
 * can't afford it, the claim is blocked. This mod does not implement chunk claiming/protection
 * itself, only the charge; FTB Chunks owns everything else.
 */
public class ChunkClaimConfig {
    public boolean enabled = true;
    public double cost = 200.0;

    public static ChunkClaimConfig defaults() {
        return new ChunkClaimConfig();
    }
}
