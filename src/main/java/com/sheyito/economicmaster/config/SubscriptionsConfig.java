package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/subscriptions.json. Subscriptions are entirely
 * player-to-player (one player offers a paid service via "/subscribe offer <precio>", others
 * subscribe to them via "/subscribe <jugador>") - the only thing configurable server-wide is
 * how often subscribers get billed.
 */
public class SubscriptionsConfig {
    public int intervalGameDays = 5;

    public static SubscriptionsConfig defaults() {
        return new SubscriptionsConfig();
    }
}
