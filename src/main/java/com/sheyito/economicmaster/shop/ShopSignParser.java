package com.sheyito.economicmaster.shop;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless reader of a sign's 4 lines of plain text into a {@link ParsedShopText}. Never
 * writes anything, never touches ownership or the world - just text in, structured data out
 * (or empty if the sign isn't a validly-formatted shop sign, in which case it's left alone as
 * an ordinary decorative sign).
 */
public final class ShopSignParser {

    private static final Pattern ACTION_LINE = Pattern.compile("^(SELL|BUY)\\s+(\\d+(?:\\.\\d+)?)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ITEM_LINE = Pattern.compile("^(\\d+)\\s+([a-z0-9_.\\-]+(?::[a-z0-9_./\\-]+)?)$", Pattern.CASE_INSENSITIVE);

    private ShopSignParser() {
    }

    public static Optional<ParsedShopText> parse(SignBlockEntity sign) {
        String ownerLine = sign.getFrontText().getMessage(0, false).getString().trim();
        String actionLine = sign.getFrontText().getMessage(1, false).getString().trim();
        String itemLine = sign.getFrontText().getMessage(2, false).getString().trim();
        return parse(ownerLine, actionLine, itemLine);
    }

    /** Pure text-in-structured-data-out core, split out from {@link #parse(SignBlockEntity)} so it's unit-testable without a live sign block entity. */
    public static Optional<ParsedShopText> parse(String ownerLineRaw, String actionLineRaw, String itemLineRaw) {
        String ownerLine = ownerLineRaw.trim();
        String actionLine = actionLineRaw.trim();
        String itemLine = itemLineRaw.trim();

        if (ownerLine.isEmpty()) {
            return Optional.empty();
        }

        Matcher actionMatcher = ACTION_LINE.matcher(actionLine);
        if (!actionMatcher.matches()) {
            return Optional.empty();
        }
        ShopAction action = ShopAction.fromSignWord(actionMatcher.group(1));
        double price;
        try {
            price = Double.parseDouble(actionMatcher.group(2));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (action == null || price <= 0) {
            return Optional.empty();
        }

        Matcher itemMatcher = ITEM_LINE.matcher(itemLine);
        if (!itemMatcher.matches()) {
            return Optional.empty();
        }
        int quantity;
        try {
            quantity = Integer.parseInt(itemMatcher.group(1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (quantity <= 0) {
            return Optional.empty();
        }

        ResourceLocation itemId = ResourceLocation.tryParse(itemMatcher.group(2));
        if (itemId == null) {
            return Optional.empty();
        }
        Optional<Item> itemLookup = BuiltInRegistries.ITEM.getOptional(itemId);
        if (itemLookup.isEmpty() || itemLookup.get() == Items.AIR) {
            return Optional.empty();
        }
        Item item = itemLookup.get();
        quantity = Math.min(quantity, item.getDefaultMaxStackSize());

        return Optional.of(new ParsedShopText(ownerLine, action, price, item, quantity));
    }
}
