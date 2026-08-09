package com.sheyito.economicmaster.trade;

import com.sheyito.economicmaster.EconomicMaster;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.Money;
import com.sheyito.economicmaster.util.TransactionSounds;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared state for one two-player trade. Fully in-memory and transient - never persisted to
 * disk, mirroring how most trade-GUI plugins work (see the accepted residual risk noted in
 * the design: a hard server crash mid-trade loses whatever was sitting in the offer slots,
 * same as it would for any unsaved container). Both players' {@link TradeMenu} instances read
 * their slots straight out of the {@link SimpleContainer}s held here, so there is no custom
 * network sync anywhere - vanilla's per-tick {@code AbstractContainerMenu#broadcastChanges()}
 * already does that for us as long as both menus' {@code Slot}s point at the same container
 * instances.
 */
public class TradeSession {

    /** One entry per currency deposit slot, in slot order. */
    public record Denomination(Item item, long value) {
    }

    public static final List<Denomination> CURRENCY_DENOMINATIONS = List.of(
            new Denomination(Items.COPPER_INGOT, 1),
            new Denomination(Items.IRON_INGOT, 10),
            new Denomination(Items.GOLD_INGOT, 100),
            new Denomination(Items.DIAMOND, 1000),
            new Denomination(Items.NETHERITE_INGOT, 10_000)
    );

    private static final int PANEL_COUNT = 9;
    private static final int TICKS_PER_PANEL = 6;
    // Package-visible (not private) so TradeSessionTest can drive tick() exactly this many
    // times instead of duplicating the constant.
    static final int TOTAL_TICKS = PANEL_COUNT * TICKS_PER_PANEL;

    private final UUID uuidA;
    private final UUID uuidB;

    private final SimpleContainer offerA = new SimpleContainer(9);
    private final SimpleContainer offerB = new SimpleContainer(9);
    private final SimpleContainer progress = new SimpleContainer(PANEL_COUNT);
    // Money is not a typed field: it is whatever sits in these slots, valued per
    // CURRENCY_DENOMINATIONS. Depositing an ingot raises the offer, taking it back out
    // (normal drag-and-drop, exactly like the item offer row) lowers it again - there is no
    // separate "remove" control because a container slot is already bidirectional.
    private final SimpleContainer moneyItemsA = new SimpleContainer(CURRENCY_DENOMINATIONS.size());
    private final SimpleContainer moneyItemsB = new SimpleContainer(CURRENCY_DENOMINATIONS.size());
    private final SimpleContainer moneyDisplayA = new SimpleContainer(1);
    private final SimpleContainer moneyDisplayB = new SimpleContainer(1);
    private final SimpleContainer confirmDisplayA = new SimpleContainer(1);
    private final SimpleContainer confirmDisplayB = new SimpleContainer(1);

    private boolean confirmedA = false;
    private boolean confirmedB = false;
    private int progressTicks = 0;

    private TradeMenu menuA;
    private TradeMenu menuB;

    private final AtomicBoolean finished = new AtomicBoolean(false);

    public TradeSession(UUID uuidA, UUID uuidB) {
        this.uuidA = uuidA;
        this.uuidB = uuidB;
        offerA.addListener(container -> onOfferMutated());
        offerB.addListener(container -> onOfferMutated());
        moneyItemsA.addListener(container -> onMoneyMutated(this.uuidA));
        moneyItemsB.addListener(container -> onMoneyMutated(this.uuidB));
        refreshProgressDisplay();
        refreshMoneyDisplay(uuidA);
        refreshMoneyDisplay(uuidB);
        refreshConfirmDisplay(uuidA);
        refreshConfirmDisplay(uuidB);
    }

    public UUID uuidA() {
        return uuidA;
    }

    public UUID uuidB() {
        return uuidB;
    }

    public UUID other(UUID viewer) {
        return viewer.equals(uuidA) ? uuidB : uuidA;
    }

    public boolean involves(UUID uuid) {
        return uuid.equals(uuidA) || uuid.equals(uuidB);
    }

    public boolean isFinished() {
        return finished.get();
    }

    /** True once both sides have confirmed - offer slots lock immediately, no one-tick gap. */
    public boolean isLocked() {
        return confirmedA && confirmedB;
    }

    void attachMenu(UUID viewer, TradeMenu menu) {
        if (viewer.equals(uuidA)) {
            menuA = menu;
        } else {
            menuB = menu;
        }
    }

    public SimpleContainer offerContainerFor(UUID uuid) {
        return uuid.equals(uuidA) ? offerA : offerB;
    }

    public SimpleContainer moneyItemsFor(UUID uuid) {
        return uuid.equals(uuidA) ? moneyItemsA : moneyItemsB;
    }

    public SimpleContainer progressContainer() {
        return progress;
    }

    public SimpleContainer moneyDisplayFor(UUID uuid) {
        return uuid.equals(uuidA) ? moneyDisplayA : moneyDisplayB;
    }

    public SimpleContainer confirmDisplayFor(UUID uuid) {
        return uuid.equals(uuidA) ? confirmDisplayA : confirmDisplayB;
    }

    /** Sum of every deposited currency item's value - this *is* the money offered, not a separate field. */
    public long moneyOffered(UUID uuid) {
        return valueOf(moneyItemsFor(uuid));
    }

    private static long valueOf(Container moneyItems) {
        long total = 0;
        for (int i = 0; i < CURRENCY_DENOMINATIONS.size() && i < moneyItems.getContainerSize(); i++) {
            ItemStack stack = moneyItems.getItem(i);
            if (!stack.isEmpty()) {
                total += (long) stack.getCount() * CURRENCY_DENOMINATIONS.get(i).value();
            }
        }
        return total;
    }

    public void toggleConfirm(UUID uuid) {
        if (finished.get()) {
            return;
        }
        if (uuid.equals(uuidA)) {
            confirmedA = !confirmedA;
        } else {
            confirmedB = !confirmedB;
        }
        if (!isLocked()) {
            progressTicks = 0;
            refreshProgressDisplay();
        }
        refreshConfirmDisplay(uuidA);
        refreshConfirmDisplay(uuidB);
    }

    /** Any item-offer change while not yet both-confirmed resets confirmation and the bar. */
    private void onOfferMutated() {
        resetConfirmationOnMutation();
    }

    private void onMoneyMutated(UUID uuid) {
        refreshMoneyDisplay(uuid);
        resetConfirmationOnMutation();
    }

    private void resetConfirmationOnMutation() {
        if (finished.get()) {
            return;
        }
        boolean wasConfirmed = confirmedA || confirmedB;
        confirmedA = false;
        confirmedB = false;
        progressTicks = 0;
        refreshProgressDisplay();
        if (wasConfirmed) {
            refreshConfirmDisplay(uuidA);
            refreshConfirmDisplay(uuidB);
        }
    }

    public void tick(MinecraftServer server) {
        if (finished.get() || !isLocked()) {
            return;
        }
        progressTicks++;
        int filledPanels = Math.min(PANEL_COUNT, progressTicks / TICKS_PER_PANEL);
        int previouslyFilled = Math.min(PANEL_COUNT, (progressTicks - 1) / TICKS_PER_PANEL);
        refreshProgressDisplay();
        if (filledPanels > previouslyFilled) {
            playProgressTickToBoth(server, 1.0f + filledPanels * 0.08f);
        }
        if (progressTicks >= TOTAL_TICKS) {
            complete(server);
        }
    }

    /**
     * Unlike the old currency-balance design, there is no "insufficient funds" failure mode
     * here anymore: the money is already physically sitting in {@link #moneyItemsA}/{@link
     * #moneyItemsB} as real items, so whatever is there at lock time is guaranteed to still be
     * there at completion time (nothing external can spend it out from under the trade).
     */
    private void complete(MinecraftServer server) {
        if (finished.get()) {
            return;
        }
        finished.set(true);

        long moneyFromA = valueOf(moneyItemsA);
        long moneyFromB = valueOf(moneyItemsB);
        clearContainer(moneyItemsA);
        clearContainer(moneyItemsB);
        if (moneyFromA > 0) {
            EconomyManager.get().give(uuidB, moneyFromA);
        }
        if (moneyFromB > 0) {
            EconomyManager.get().give(uuidA, moneyFromB);
        }

        returnItems(server, offerA, uuidB);
        returnItems(server, offerB, uuidA);

        notifyBoth(server, "§a[Sheyito's currency] §fIntercambio completado.");
        playTransactionSoundToBoth(server, true);
        closeBothMenus(server);
    }

    public void abort(MinecraftServer server, String reason) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        returnItems(server, offerA, uuidA);
        returnItems(server, offerB, uuidB);
        returnItems(server, moneyItemsA, uuidA);
        returnItems(server, moneyItemsB, uuidB);
        notifyBoth(server, "§c[Sheyito's currency] §fIntercambio cancelado: " + reason);
        playTransactionSoundToBoth(server, false);
        closeBothMenus(server);
    }

    private static void clearContainer(SimpleContainer container) {
        for (int i = 0; i < container.getContainerSize(); i++) {
            container.setItem(i, ItemStack.EMPTY);
        }
    }

    private void returnItems(MinecraftServer server, SimpleContainer source, UUID recipientUuid) {
        ServerPlayer recipient = server.getPlayerList().getPlayer(recipientUuid);
        for (int i = 0; i < source.getContainerSize(); i++) {
            ItemStack stack = source.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            source.setItem(i, ItemStack.EMPTY);
            if (recipient != null) {
                recipient.getInventory().placeItemBackInInventory(stack);
            } else {
                EconomicMaster.LOGGER.error("EconomicMaster: no se pudo devolver {} a {} tras un trade - el jugador no estaba disponible.", stack, recipientUuid);
            }
        }
    }

    private void closeBothMenus(MinecraftServer server) {
        ServerPlayer a = server.getPlayerList().getPlayer(uuidA);
        if (a != null && a.containerMenu == menuA) {
            a.closeContainer();
        }
        ServerPlayer b = server.getPlayerList().getPlayer(uuidB);
        if (b != null && b.containerMenu == menuB) {
            b.closeContainer();
        }
    }

    private void notifyBoth(MinecraftServer server, String message) {
        ServerPlayer a = server.getPlayerList().getPlayer(uuidA);
        if (a != null) {
            a.sendSystemMessage(Component.literal(message));
        }
        ServerPlayer b = server.getPlayerList().getPlayer(uuidB);
        if (b != null) {
            b.sendSystemMessage(Component.literal(message));
        }
    }

    private void playProgressTickToBoth(MinecraftServer server, float pitch) {
        ServerPlayer a = server.getPlayerList().getPlayer(uuidA);
        if (a != null) {
            a.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 1.0f, pitch);
        }
        ServerPlayer b = server.getPlayerList().getPlayer(uuidB);
        if (b != null) {
            b.playNotifySound(SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.MASTER, 1.0f, pitch);
        }
    }

    private void playTransactionSoundToBoth(MinecraftServer server, boolean success) {
        ServerPlayer a = server.getPlayerList().getPlayer(uuidA);
        if (a != null) {
            if (success) {
                TransactionSounds.success(a);
            } else {
                TransactionSounds.failure(a);
            }
        }
        ServerPlayer b = server.getPlayerList().getPlayer(uuidB);
        if (b != null) {
            if (success) {
                TransactionSounds.success(b);
            } else {
                TransactionSounds.failure(b);
            }
        }
    }

    private void refreshProgressDisplay() {
        int filledPanels = Math.min(PANEL_COUNT, progressTicks / TICKS_PER_PANEL);
        for (int i = 0; i < PANEL_COUNT; i++) {
            ItemStack pane = new ItemStack(i < filledPanels ? Items.LIME_STAINED_GLASS_PANE : Items.RED_STAINED_GLASS_PANE);
            pane.set(DataComponents.CUSTOM_NAME, Component.literal(isLocked() ? "§aConfirmando..." : "§7Esperando confirmacion"));
            progress.setItem(i, pane);
        }
    }

    private void refreshMoneyDisplay(UUID uuid) {
        SimpleContainer container = moneyDisplayFor(uuid);
        long amount = moneyOffered(uuid);
        ItemStack stack = new ItemStack(Items.GOLD_INGOT, (int) Math.max(1, Math.min(64, 1 + amount / 100)));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("§6Dinero ofrecido: §e" + Money.format(amount)));
        container.setItem(0, stack);
    }

    private void refreshConfirmDisplay(UUID uuid) {
        SimpleContainer container = confirmDisplayFor(uuid);
        boolean confirmed = uuid.equals(uuidA) ? confirmedA : confirmedB;
        ItemStack stack = new ItemStack(confirmed ? Items.LIME_DYE : Items.GRAY_DYE);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(confirmed ? "§aConfirmado" : "§7Click para confirmar"));
        container.setItem(0, stack);
    }
}
