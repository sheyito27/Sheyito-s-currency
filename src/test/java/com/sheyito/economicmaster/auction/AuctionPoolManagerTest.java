package com.sheyito.economicmaster.auction;

import com.sheyito.economicmaster.TestBootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure storage backing "/sc liquidation withdraw" - a FIFO queue with no auction logic of its own. */
class AuctionPoolManagerTest {

    @BeforeAll
    static void bootstrap() {
        TestBootstrap.ensure();
    }

    @Test
    void newPoolIsEmpty() {
        AuctionPoolManager pool = AuctionPoolManager.createForTesting();
        assertTrue(pool.list().isEmpty());
        assertTrue(pool.retrieveNext().isEmpty());
    }

    @Test
    void addedItemsShowUpInList() {
        AuctionPoolManager pool = AuctionPoolManager.createForTesting();
        UUID victim = UUID.randomUUID();

        pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 5L);

        List<AuctionPoolManager.PooledItem> items = pool.list();
        assertEquals(1, items.size());
        assertEquals(Items.DIAMOND_SWORD, items.get(0).stack().getItem());
        assertEquals(victim, items.get(0).seizedFromUuid());
        assertEquals("Fulano", items.get(0).seizedFromName());
        assertEquals(5L, items.get(0).addedAtGameDay());
    }

    @Test
    void retrieveNextPopsInFifoOrderAndRemovesFromThePool() {
        AuctionPoolManager pool = AuctionPoolManager.createForTesting();
        UUID victim = UUID.randomUUID();
        pool.add(new ItemStack(Items.DIAMOND_SWORD), victim, "Fulano", 1L);
        pool.add(new ItemStack(Items.NETHERITE_AXE), victim, "Fulano", 2L);

        Optional<AuctionPoolManager.PooledItem> first = pool.retrieveNext();

        assertTrue(first.isPresent());
        assertEquals(Items.DIAMOND_SWORD, first.get().stack().getItem());
        assertEquals(1, pool.list().size());
        assertEquals(Items.NETHERITE_AXE, pool.list().get(0).stack().getItem());
    }
}
