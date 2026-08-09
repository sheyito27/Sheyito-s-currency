package com.sheyito.economicmaster.shop;

import com.sheyito.economicmaster.TestBootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parses the 4-line shop sign format that creates/updates a chest shop. */
class ShopSignParserTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    @Test
    void parsesAValidSellSign() {
        Optional<ParsedShopText> result = ShopSignParser.parse("Sheyito", "SELL 10.5", "1 minecraft:diamond");

        assertTrue(result.isPresent());
        ParsedShopText parsed = result.get();
        assertEquals("Sheyito", parsed.ownerNameOnSign());
        assertEquals(ShopAction.SELL, parsed.action());
        assertEquals(10.5, parsed.price());
        assertEquals(Items.DIAMOND, parsed.item());
        assertEquals(1, parsed.quantity());
    }

    @Test
    void parsesAValidBuySignCaseInsensitively() {
        Optional<ParsedShopText> result = ShopSignParser.parse("Sheyito", "buy 5", "64 diamond");

        assertTrue(result.isPresent());
        assertEquals(ShopAction.BUY, result.get().action());
        assertEquals(Items.DIAMOND, result.get().item());
    }

    @Test
    void resolvesItemIdWithoutExplicitNamespace() {
        Optional<ParsedShopText> result = ShopSignParser.parse("Sheyito", "SELL 1", "1 emerald");
        assertTrue(result.isPresent());
        assertEquals(Items.EMERALD, result.get().item());
    }

    @Test
    void rejectsEmptyOwnerLine() {
        assertFalse(ShopSignParser.parse("", "SELL 10", "1 diamond").isPresent());
    }

    @Test
    void rejectsUnrecognizedAction() {
        assertFalse(ShopSignParser.parse("Sheyito", "TRADE 10", "1 diamond").isPresent());
    }

    @Test
    void rejectsZeroOrNegativePrice() {
        assertFalse(ShopSignParser.parse("Sheyito", "SELL 0", "1 diamond").isPresent());
    }

    @Test
    void rejectsMalformedPrice() {
        assertFalse(ShopSignParser.parse("Sheyito", "SELL abc", "1 diamond").isPresent());
    }

    @Test
    void rejectsZeroQuantity() {
        assertFalse(ShopSignParser.parse("Sheyito", "SELL 10", "0 diamond").isPresent());
    }

    @Test
    void rejectsUnknownItemId() {
        assertFalse(ShopSignParser.parse("Sheyito", "SELL 10", "1 not_a_real_item").isPresent());
    }

    @Test
    void rejectsAirAsAnItem() {
        assertFalse(ShopSignParser.parse("Sheyito", "SELL 10", "1 minecraft:air").isPresent());
    }

    @Test
    void clampsQuantityToItemMaxStackSize() {
        // ender_pearl has a max stack size of 16 in vanilla
        Optional<ParsedShopText> result = ShopSignParser.parse("Sheyito", "SELL 1", "64 ender_pearl");
        assertTrue(result.isPresent());
        assertEquals(16, result.get().quantity());
    }

    @Test
    void rejectsMalformedItemLine() {
        assertFalse(ShopSignParser.parse("Sheyito", "SELL 10", "diamond 1").isPresent());
    }
}
