package com.sheyito.economicmaster.data;

import java.util.ArrayList;
import java.util.List;

/**
 * On-disk shape of &lt;world&gt;/sheyitoscurrency/subscriptions_data.json: a flat list of
 * direct player-to-player recurring payments (see {@link PlayerSubscription}).
 */
public class SubscriptionData {
    public List<PlayerSubscription> subscriptions = new ArrayList<>();

    public static SubscriptionData empty() {
        return new SubscriptionData();
    }
}
