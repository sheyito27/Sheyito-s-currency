package com.sheyito.economicmaster.data;

/**
 * One buyer's active subscription to a seller's offer. {@code price} is locked in at the
 * moment the buyer subscribed, so a seller changing their offer price later only affects
 * new subscribers, not existing ones.
 */
public class PlayerSubscription {
    public String sellerUuid;
    public double price;
    public long nextChargeGameDay;
    public boolean active = true;

    public PlayerSubscription() {
    }

    public PlayerSubscription(String sellerUuid, double price, long nextChargeGameDay) {
        this.sellerUuid = sellerUuid;
        this.price = price;
        this.nextChargeGameDay = nextChargeGameDay;
    }
}
