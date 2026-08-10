package com.sheyito.economicmaster.data;

/**
 * A direct player-to-player recurring payment: {@code buyerUuid} pays {@code price} to
 * {@code sellerUuid} every intervalGameDays (subscriptions.json), starting immediately when
 * created. {@code description} is a free-text note set by the buyer (e.g. "renta del terreno"),
 * purely informational. A player can hold any number of these, both as payer and as recipient.
 */
public class PlayerSubscription {
    public String buyerUuid;
    public String sellerUuid;
    public double price;
    public int intervalGameDays;
    public String description = "";
    public long nextChargeGameDay;
    public boolean active = true;

    public PlayerSubscription() {
    }

    public PlayerSubscription(String buyerUuid, String sellerUuid, double price, int intervalGameDays, String description, long nextChargeGameDay) {
        this.buyerUuid = buyerUuid;
        this.sellerUuid = sellerUuid;
        this.price = price;
        this.intervalGameDays = intervalGameDays;
        this.description = description;
        this.nextChargeGameDay = nextChargeGameDay;
    }
}
