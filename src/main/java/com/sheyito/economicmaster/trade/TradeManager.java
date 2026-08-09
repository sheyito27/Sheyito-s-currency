package com.sheyito.economicmaster.trade;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fully in-memory, per-server-session registry of pending trade invites and active
 * {@link TradeSession}s. Nothing here is persisted - a trade is a live negotiation, not
 * durable state, same reasoning as why {@link TradeSession} itself never touches disk.
 */
public class TradeManager {

    private static final int INVITE_TIMEOUT_TICKS = 20 * 60;

    private static volatile TradeManager instance;

    private final MinecraftServer server;
    private final Map<UUID, TradeSession> sessionsByPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, TradeInvite> pendingInvites = new ConcurrentHashMap<>();

    private record TradeInvite(UUID fromUuid, long expiresAtTick) {
    }

    private TradeManager(MinecraftServer server) {
        this.server = server;
    }

    public static void init(MinecraftServer server) {
        instance = new TradeManager(server);
    }

    public static TradeManager get() {
        return instance;
    }

    public static void shutdown() {
        TradeManager manager = instance;
        if (manager == null) {
            return;
        }
        for (TradeSession session : new LinkedHashSet<>(manager.sessionsByPlayer.values())) {
            session.abort(manager.server, "el servidor se esta deteniendo");
        }
        instance = null;
    }

    public boolean isBusy(UUID uuid) {
        if (sessionsByPlayer.containsKey(uuid) || pendingInvites.containsKey(uuid)) {
            return true;
        }
        for (TradeInvite invite : pendingInvites.values()) {
            if (invite.fromUuid().equals(uuid)) {
                return true;
            }
        }
        return false;
    }

    public TradeSession getSession(UUID uuid) {
        return sessionsByPlayer.get(uuid);
    }

    public void invite(ServerPlayer from, ServerPlayer to) {
        pendingInvites.put(to.getUUID(), new TradeInvite(from.getUUID(), server.getTickCount() + INVITE_TIMEOUT_TICKS));

        from.sendSystemMessage(Component.literal("§a[Sheyito's currency] §fInvitacion de intercambio enviada a " + to.getGameProfile().getName() + "."));

        Component acceptButton = Component.literal("[Aceptar]")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/trade accept")));
        to.sendSystemMessage(Component.literal("§a[Sheyito's currency] §f" + from.getGameProfile().getName() + " te invito a intercambiar. ").append(acceptButton));
    }

    public boolean accept(ServerPlayer accepter) {
        TradeInvite invite = pendingInvites.remove(accepter.getUUID());
        if (invite == null) {
            return false;
        }
        ServerPlayer inviter = server.getPlayerList().getPlayer(invite.fromUuid());
        if (inviter == null) {
            accepter.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fEse jugador ya no esta conectado."));
            return false;
        }
        if (isBusy(accepter.getUUID()) || isBusy(inviter.getUUID())) {
            accepter.sendSystemMessage(Component.literal("§c[Sheyito's currency] §fUno de los dos ya esta en otro intercambio."));
            return false;
        }

        TradeSession session = new TradeSession(inviter.getUUID(), accepter.getUUID());
        sessionsByPlayer.put(inviter.getUUID(), session);
        sessionsByPlayer.put(accepter.getUUID(), session);

        inviter.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new TradeMenu(id, inv, session, inviter.getUUID()),
                Component.literal("Intercambio: " + accepter.getGameProfile().getName())));
        accepter.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new TradeMenu(id, inv, session, accepter.getUUID()),
                Component.literal("Intercambio: " + inviter.getGameProfile().getName())));
        return true;
    }

    public boolean deny(ServerPlayer decliner) {
        TradeInvite invite = pendingInvites.remove(decliner.getUUID());
        if (invite == null) {
            return false;
        }
        ServerPlayer inviter = server.getPlayerList().getPlayer(invite.fromUuid());
        if (inviter != null) {
            inviter.sendSystemMessage(Component.literal("§c[Sheyito's currency] §f" + decliner.getGameProfile().getName() + " rechazo tu invitacion de intercambio."));
        }
        return true;
    }

    public void cancel(UUID uuid, String reason) {
        TradeSession session = sessionsByPlayer.get(uuid);
        if (session != null) {
            session.abort(server, reason);
        }
    }

    public void handleDisconnect(UUID uuid) {
        pendingInvites.remove(uuid);
        pendingInvites.entrySet().removeIf(entry -> entry.getValue().fromUuid().equals(uuid));
        TradeSession session = sessionsByPlayer.get(uuid);
        if (session != null) {
            session.abort(server, "el otro jugador se desconecto");
        }
    }

    public void handleMenuClosed(UUID uuid, MinecraftServer server) {
        TradeSession session = sessionsByPlayer.get(uuid);
        if (session != null && !session.isFinished()) {
            session.abort(server, "cerraste la ventana de intercambio");
        }
    }

    public void tickAll() {
        expireInvites();
        Set<TradeSession> uniqueSessions = new LinkedHashSet<>(sessionsByPlayer.values());
        for (TradeSession session : uniqueSessions) {
            session.tick(server);
        }
        if (!uniqueSessions.isEmpty()) {
            sessionsByPlayer.entrySet().removeIf(entry -> entry.getValue().isFinished());
        }
    }

    private void expireInvites() {
        long now = server.getTickCount();
        pendingInvites.entrySet().removeIf(entry -> entry.getValue().expiresAtTick() <= now);
    }
}
