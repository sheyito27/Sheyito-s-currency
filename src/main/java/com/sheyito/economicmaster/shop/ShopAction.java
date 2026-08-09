package com.sheyito.economicmaster.shop;

/** SELL: the shop sells from the chest to whoever clicks. BUY: the shop buys into the chest. */
public enum ShopAction {
    SELL,
    BUY;

    public static ShopAction fromSignWord(String word) {
        if ("SELL".equalsIgnoreCase(word)) {
            return SELL;
        }
        if ("BUY".equalsIgnoreCase(word)) {
            return BUY;
        }
        return null;
    }
}
