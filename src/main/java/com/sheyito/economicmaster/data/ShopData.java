package com.sheyito.economicmaster.data;

import java.util.ArrayList;
import java.util.List;

/** On-disk shape of &lt;world&gt;/sheyitoscurrency/shops.json. */
public class ShopData {
    public List<ShopRecord> shops = new ArrayList<>();

    public static ShopData empty() {
        return new ShopData();
    }
}
