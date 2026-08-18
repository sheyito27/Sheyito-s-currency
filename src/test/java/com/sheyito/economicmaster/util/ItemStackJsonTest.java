package com.sheyito.economicmaster.util;

import com.sheyito.economicmaster.TestBootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The liquidation feature vaults real seized items across server restarts, so this round-trip
 * (through ItemStack's own vanilla CODEC) has to preserve more than just the item id/count. */
class ItemStackJsonTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    @Test
    void roundTripsItemAndCount() {
        ItemStack original = new ItemStack(Items.IRON_INGOT, 42);

        ItemStack decoded = ItemStackJson.decode(ItemStackJson.encode(original));

        assertTrue(ItemStack.isSameItem(original, decoded));
        assertEquals(42, decoded.getCount());
    }

    @Test
    void roundTripsCustomDataComponentsLikeACustomName() {
        ItemStack original = new ItemStack(Items.NETHERITE_SWORD);
        original.set(DataComponents.CUSTOM_NAME, Component.literal("Espada Legendaria"));

        ItemStack decoded = ItemStackJson.decode(ItemStackJson.encode(original));

        assertEquals(Component.literal("Espada Legendaria"), decoded.get(DataComponents.CUSTOM_NAME));
        assertTrue(ItemStack.isSameItemSameComponents(original, decoded));
    }

    @Test
    void roundTripsDurability() {
        ItemStack original = new ItemStack(Items.DIAMOND_PICKAXE);
        original.setDamageValue(500);

        ItemStack decoded = ItemStackJson.decode(ItemStackJson.encode(original));

        assertEquals(500, decoded.getDamageValue());
    }
}
