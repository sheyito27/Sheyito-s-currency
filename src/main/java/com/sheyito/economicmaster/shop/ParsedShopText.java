package com.sheyito.economicmaster.shop;

import net.minecraft.world.item.Item;

/** Pure result of reading a sign's 4 lines - no world/ownership context yet. */
public record ParsedShopText(String ownerNameOnSign, ShopAction action, double price, Item item, int quantity) {
}
