package com.sheyito.economicmaster.config;

/**
 * Backing schema for config/sheyitoscurrency/subscriptions.json. Subscriptions are entirely
 * player-to-player, direct pledges ("/subscribe <jugador> <dinero> [descripcion]" starts paying
 * that player) - the only thing configurable server-wide is how often payers get billed.
 */
public class SubscriptionsConfig {
    public int intervalGameDays = 5;

    public static SubscriptionsConfig defaults() {
        return new SubscriptionsConfig();
    }
}
