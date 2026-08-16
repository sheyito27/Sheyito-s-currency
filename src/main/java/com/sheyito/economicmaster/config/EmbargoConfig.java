package com.sheyito.economicmaster.config;

import java.util.List;

/**
 * Backing schema for config/sheyitoscurrency/embargo.json. When a player's balance goes
 * negative (today only via {@code /eco charge} - the future "pagos obligatorios" feature will
 * be the real gameplay trigger), they get {@link #graceSeconds} real seconds - paused while
 * offline - to pay it off before their equipped and inventory armor/weapons/tools are seized.
 * {@link #minVotersToClose} and {@link #minVoteGameDays} gate when the community vote on which
 * seized item goes to the auction pool is allowed to close (both conditions required).
 * {@link #auctionDurationGameDays} and {@link #bidIncrements} configure the bidding auction that
 * the winning item then goes through in the pool itself - a separate, independent timer from the
 * seizure vote above.
 */
public class EmbargoConfig {
    public boolean enabled = true;
    public int graceSeconds = 30;
    public int minVotersToClose = 2;
    public int minVoteGameDays = 2;
    public int auctionDurationGameDays = 3;
    public List<Double> bidIncrements = List.of(10.0, 100.0, 1000.0);

    public static EmbargoConfig defaults() {
        return new EmbargoConfig();
    }
}
