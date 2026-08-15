package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/embargo.json. When a player's balance goes
 * negative (today only via {@code /eco charge} - the future "pagos obligatorios" feature will
 * be the real gameplay trigger), they get {@link #graceSeconds} real seconds - paused while
 * offline - to pay it off before their equipped and inventory armor/weapons/tools are seized.
 * {@link #minVotersToClose} and {@link #minVoteGameDays} gate when the community vote on which
 * seized item goes to the auction pool is allowed to close (both conditions required).
 */
public class EmbargoConfig {
    public boolean enabled = true;
    public int graceSeconds = 30;
    public int minVotersToClose = 2;
    public int minVoteGameDays = 2;

    public static EmbargoConfig defaults() {
        return new EmbargoConfig();
    }
}
