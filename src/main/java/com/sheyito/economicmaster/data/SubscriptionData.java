package com.sheyito.economicmaster.data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * On-disk shape of &lt;world&gt;/sheyitoscurrency/subscriptions_data.json.
 * {@code offers}: seller uuid -> price they charge subscribers.
 * {@code subscriptions}: buyer uuid -> their active subscription to a seller.
 */
public class SubscriptionData {
    public Map<String, Double> offers = new LinkedHashMap<>();
    public Map<String, PlayerSubscription> subscriptions = new LinkedHashMap<>();

    public static SubscriptionData empty() {
        return new SubscriptionData();
    }
}
