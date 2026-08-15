package com.sheyito.economicmaster.monopoly;

import com.sheyito.economicmaster.EconomicMaster;
import com.sheyito.economicmaster.config.ConfigManager;
import com.sheyito.economicmaster.config.MonopolyConfig;
import com.sheyito.economicmaster.config.MonopolyEventEntry;
import com.sheyito.economicmaster.data.DataPaths;
import com.sheyito.economicmaster.data.MonopolyData;
import com.sheyito.economicmaster.economy.EconomyManager;
import com.sheyito.economicmaster.util.JsonFileUtil;
import com.sheyito.economicmaster.util.Money;
import com.sheyito.economicmaster.util.TransactionSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Corazón de Monopoly: sortea un evento económico cada {@code eventsPerDay} días de juego
 * (medidos con el tick del overworld, igual que {@code GameTime}, así que el tiempo offline
 * nunca cuenta). El evento elegido permanece activo hasta el siguiente sorteo, sobrevive a
 * reinicios (se persiste su id y sus parámetros ya sorteados), y el resto del mod consulta sus
 * efectos a través de los getters ({@link #salaryMultiplier()}, {@link #questRewardMultiplier()},
 * {@link #wantedMob()}...) que devuelven valores neutros cuando no hay evento activo.
 *
 * <p>También aloja el subsistema de cara o cruz ({@link #coinflipVsHouse} y la versión P2P con
 * invitación pendiente), incluyendo la comisión de La Casa que se quema como sink de dinero.
 */
public class MonopolyManager {

    private static final long TICKS_PER_DAY = 24000L;
    private static final int COINFLIP_INVITE_TIMEOUT_TICKS = 20 * 60;

    /** Ventana (en ticks) en la que un jugador cuenta como contribuidor de daño de un mob buscado. */
    private static final int CONTRIBUTOR_TTL_TICKS = 20 * 60;

    private static volatile MonopolyManager instance;

    /** Invitación pendiente de cara o cruz P2P, keyed por el UUID de a quién toca aceptar. */
    private record CoinflipInvite(String challengerUuid, double amount, long expiresAtTick) {
    }

    private final Path file;
    private final Map<String, CoinflipInvite> pendingCoinflipInvites = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    /** Quién dañó a cada mob buscado vivo: UUID del mob -> {jugadores (UUID -> nombre), último tick de daño}. */
    private record BountyTarget(Map<UUID, String> players, long lastHurtTick) {
    }

    private final Map<UUID, BountyTarget> damageContributors = new ConcurrentHashMap<>();

    private long lastPeriodIndex = -1;
    private String currentEventId = null;
    private Double currentMultiplier = null;
    private String currentMob = null;
    private String currentMessage = null;

    /** Muertes del mob buscado que ya pagaron bounty en el evento activo (tipo MOB_WANTED). */
    private int mobWantedKills = 0;

    /** Fuente de números aleatorios uniformes en [0,1) — inyectable para los tests. */
    private Supplier<Double> rng = () -> ThreadLocalRandom.current().nextDouble();

    private MonopolyManager(Path file) {
        this.file = file;
    }

    public static void init(MinecraftServer server) {
        MonopolyManager manager = new MonopolyManager(DataPaths.dataDir(server).resolve("monopoly_data.json"));
        manager.load();
        instance = manager;
    }

    public static MonopolyManager get() {
        return instance;
    }

    /** Test-only: instancia en memoria que nunca toca disco (no llama a load() ni save()). */
    static MonopolyManager createForTesting() {
        return new MonopolyManager(Path.of("build", "test-tmp", "unused-monopoly-test-file.json"));
    }

    /** Test seam: instala {@code manager} como el singleton que devuelve {@link #get()}. */
    static void installForTesting(MonopolyManager manager) {
        instance = manager;
    }

    /** Test seam: fija la fuente de aleatoriedad. */
    void setRng(Supplier<Double> rng) {
        this.rng = rng;
    }

    /** Test seam: expone el último periodo ya sorteado. */
    long lastProcessedPeriod() {
        return lastPeriodIndex;
    }

    public static void shutdown() {
        if (instance != null) {
            instance.save();
            instance = null;
        }
    }

    private void load() {
        MonopolyData data = JsonFileUtil.loadOrCreate(file, MonopolyData.class, MonopolyData::empty);
        lastPeriodIndex = data.lastPeriodIndex;
        currentEventId = data.currentEventId;
        currentMultiplier = data.currentMultiplier;
        currentMob = data.currentMob;
        currentMessage = data.currentMessage;
        mobWantedKills = Math.max(0, data.currentMobKills);
    }

    public void saveIfDirty() {
        if (dirty.compareAndSet(true, false)) {
            save();
        }
    }

    public void save() {
        MonopolyData data = new MonopolyData();
        data.lastPeriodIndex = lastPeriodIndex;
        data.currentEventId = currentEventId;
        data.currentMultiplier = currentMultiplier;
        data.currentMob = currentMob;
        data.currentMessage = currentMessage;
        data.currentMobKills = mobWantedKills;
        JsonFileUtil.save(file, data);
    }

    // ------------------------------------------------------------------
    // Consultas del evento activo (lo que leen el resto de managers/commands)
    // ------------------------------------------------------------------

    public String currentEventId() {
        return currentEventId;
    }

    public boolean isActive() {
        return currentEventId != null;
    }

    /** La entrada de configuración del evento activo, o {@code null} si ya no existe en el JSON. */
    public MonopolyEventEntry currentEventConfig() {
        if (currentEventId == null) {
            return null;
        }
        for (MonopolyEventEntry entry : ConfigManager.monopoly().events) {
            if (currentEventId.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }

    private EventType eventType() {
        MonopolyEventEntry entry = currentEventConfig();
        return entry == null ? null : EventType.fromId(entry.type);
    }

    /** Multiplicador de salario del evento activo; 1.0 si no hay evento de ese tipo. */
    public double salaryMultiplier() {
        if (currentMultiplier != null && eventType() == EventType.SALARY_MULTIPLIER) {
            return currentMultiplier;
        }
        return 1.0;
    }

    /** Multiplicador de recompensas de misiones; 1.0 si no hay evento de ese tipo. */
    public double questRewardMultiplier() {
        if (currentMultiplier != null && eventType() == EventType.QUEST_REWARD_MULTIPLIER) {
            return currentMultiplier;
        }
        return 1.0;
    }

    /** true si el evento activo es un mob buscado con un mob ya sorteado. */
    public boolean isMobWanted() {
        return currentMob != null && eventType() == EventType.MOB_WANTED;
    }

    /** Id de entidad del mob buscado, o {@code null}. */
    public String wantedMob() {
        return currentMob;
    }

    /** El mensaje ya sorteado para el evento activo (template en bruto, sin sustituir), o {@code null} si se usa el default. */
    public String currentMessage() {
        return currentMessage;
    }

    /** Recompensa extra configurada por cada kill del mob buscado. */
    public double wantedBounty() {
        MonopolyEventEntry entry = currentEventConfig();
        return entry == null ? 0.0 : entry.bounty;
    }

    /** Límite configurado de muertes del mob buscado que pagan en un evento; 0 = sin límite. */
    public int mobWantedMaxKills() {
        MonopolyEventEntry entry = currentEventConfig();
        return entry == null ? 0 : Math.max(0, entry.maxKills);
    }

    /** Muertes del mob buscado que ya pagaron bounty en el evento activo. */
    public int currentMobKills() {
        return mobWantedKills;
    }

    /** true si matar al mob buscado sigue pagando bounty (no se ha agotado el cupo). */
    public boolean mobWantedPayoutActive() {
        return isMobWanted() && !mobBountyExhausted();
    }

    /** true si el cupo de muertes pagadas se agotó: el evento sigue activo pero ya no paga. */
    public boolean mobBountyExhausted() {
        return mobWantedMaxKills() > 0 && mobWantedKills >= mobWantedMaxKills();
    }

    // ------------------------------------------------------------------
    // Contribuidores de daño del mob buscado (reparto del bounty)
    // ------------------------------------------------------------------

    /**
     * Registra que {@code playerId} dañó al mob {@code victimId}. El nombre se guarda para poder
     * mostrar el mensaje aunque el jugador se vaya antes de que el mob muera. Los objetivos que
     * llevan más de {@link #CONTRIBUTOR_TTL_TICKS} ticks sin recibir daño se olvidan (mobs que se
     * despawnean o vagan sin morir nunca).
     */
    public void recordDamage(UUID victimId, UUID playerId, String playerName, long tick) {
        pruneStaleContributors(tick);
        damageContributors.compute(victimId, (key, target) -> {
            BountyTarget current = target == null ? new BountyTarget(new HashMap<>(), tick) : target;
            current.players().put(playerId, playerName);
            return new BountyTarget(current.players(), tick);
        });
    }

    /** Copia inmutable de {UUID del contribuidor -> nombre} de los jugadores que dañaron a {@code victimId}. */
    public Map<UUID, String> contributorNames(UUID victimId) {
        BountyTarget target = damageContributors.get(victimId);
        return target == null ? Map.of() : Map.copyOf(target.players());
    }

    /** Olvida a los contribuidores de un mob (se llama al morir o al terminar el evento). */
    public void forgetContributors(UUID victimId) {
        damageContributors.remove(victimId);
    }

    /** Elimina objetivos que llevan más de {@link #CONTRIBUTOR_TTL_TICKS} ticks sin recibir daño. */
    public void pruneStaleContributors(long currentTick) {
        damageContributors.entrySet().removeIf(entry -> currentTick - entry.getValue().lastHurtTick() > CONTRIBUTOR_TTL_TICKS);
    }

    /**
     * Parte equitativa de {@code bounty} entre {@code contributors} jugadores. El resto que no
     * divida exacto al redondear a céntimos no se acuña (sink), así nunca se acuña de más.
     */
    public static double bountyShare(double bounty, int contributors) {
        if (contributors <= 0) {
            return 0.0;
        }
        return Money.round(bounty / contributors);
    }

    /**
     * Registra una muerte pagada del mob buscado y, cuando se agota el cupo {@code maxKills},
     * avisa de que la recompensa deja de pagar. El evento sigue activo hasta el siguiente sorteo.
     */
    public void onMobWantedKilled(MinecraftServer server) {
        mobWantedKills++;
        dirty.set(true);
        if (!mobBountyExhausted()) {
            return;
        }
        broadcast(server, "La recompensa por " + friendlyMobName(currentMob) + " se ha agotado; ya no paga hasta el proximo evento.");
    }

    /** true si el evento activo habilita el cara o cruz contra La Casa. */
    public boolean isCoinflipActive() {
        return eventType() == EventType.HOUSE_COINFLIP;
    }

    /** Comisión (tanto por uno) de La Casa sobre cada apuesta del evento activo. */
    public double houseCommission() {
        MonopolyEventEntry entry = currentEventConfig();
        return entry == null ? 0.05 : Math.max(0.0, entry.commission);
    }

    /** Probabilidad (0..1) de ganar el cara o cruz del evento activo. */
    public double winChance() {
        MonopolyEventEntry entry = currentEventConfig();
        if (entry == null) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, entry.winChance));
    }

    /** Ticks que faltan para el siguiente sorteo (para /monopoly status). */
    public long ticksUntilNext(MinecraftServer server) {
        long periodLength = periodLengthTicks();
        return periodLength - (server.overworld().getGameTime() % periodLength);
    }

    // ------------------------------------------------------------------
    // Sorteo y ciclo del evento
    // ------------------------------------------------------------------

    /** Llamado por el scheduler cada ~30s: sortea en cada frontera de periodo. */
    public void tick(MinecraftServer server) {
        expireCoinflipInvites(server);
        pruneStaleContributors(server.overworld().getGameTime());

        MonopolyConfig config = ConfigManager.monopoly();
        if (!config.enabled) {
            clearEvent();
            return;
        }

        long period = currentPeriod(server);
        if (period > lastPeriodIndex) {
            roll(server, null);
            lastPeriodIndex = period;
            dirty.set(true);
        }
    }

    /**
     * Sorteo ponderado sobre los eventos habilitados y válidos de monopoly.json. Si se pasa
     * {@code forcedId}, se elige ese evento en concreto (o ninguno si no es válido).
     */
    public void roll(MinecraftServer server, String forcedId) {
        MonopolyConfig config = ConfigManager.monopoly();
        if (!config.enabled && forcedId == null) {
            clearEvent();
            return;
        }

        List<MonopolyEventEntry> candidates = config.events.stream()
                .filter(e -> e.enabled && e.weight > 0 && isValid(e))
                .toList();

        MonopolyEventEntry chosen;
        if (forcedId != null) {
            chosen = candidates.stream()
                    .filter(e -> forcedId.equals(e.id))
                    .findFirst()
                    .orElse(null);
        } else {
            chosen = weightedPick(candidates);
        }

        if (chosen == null) {
            EconomicMaster.LOGGER.warn("Sheyito's currency: monopoly sin eventos validos habilitados, se deja sin evento activo.");
            clearEvent();
            return;
        }

        currentEventId = chosen.id;
        currentMultiplier = resolveMultiplier(chosen);
        currentMob = resolveMob(chosen);
        currentMessage = resolveMessage(chosen);
        mobWantedKills = 0;
        dirty.set(true);

        broadcast(server, "¡EVENTO! " + formatMessage(chosen));
        EconomicMaster.LOGGER.info("Sheyito's currency: monopoly activa evento {} (tipo {})", chosen.id, chosen.type);
    }

    /** Fuerza un sorteo ahora mismo (admin). El siguiente sorteo volverá en la próxima frontera. */
    public void forceRoll(MinecraftServer server, String id) {
        roll(server, id);
        lastPeriodIndex = currentPeriod(server);
        dirty.set(true);
    }

    /** Termina el evento actual sin sortear otro: el próximo evento llega en la frontera siguiente. */
    public void endNow(MinecraftServer server) {
        clearEvent();
        lastPeriodIndex = currentPeriod(server);
        dirty.set(true);
        broadcast(server, "El evento actual ha terminado. El próximo evento llegará en el siguiente periodo.");
    }

    private long currentPeriod(MinecraftServer server) {
        return server.overworld().getGameTime() / periodLengthTicks();
    }

    private long periodLengthTicks() {
        int eventsPerDay = Math.max(1, ConfigManager.monopoly().eventsPerDay);
        return TICKS_PER_DAY / eventsPerDay;
    }

    private void clearEvent() {
        if (currentEventId == null && currentMultiplier == null && currentMob == null && currentMessage == null) {
            damageContributors.clear();
            mobWantedKills = 0;
            return;
        }
        currentEventId = null;
        currentMultiplier = null;
        currentMob = null;
        currentMessage = null;
        mobWantedKills = 0;
        damageContributors.clear();
        dirty.set(true);
    }

    /** Un evento es sorteable si su tipo existe y tiene los campos que su tipo necesita. */
    private static boolean isValid(MonopolyEventEntry e) {
        EventType type = EventType.fromId(e.type);
        if (type == null) {
            return false;
        }
        return switch (type) {
            case SALARY_MULTIPLIER, QUEST_REWARD_MULTIPLIER ->
                    e.multipliers != null && !e.multipliers.isEmpty();
            case MOB_WANTED -> e.mobs != null && !e.mobs.isEmpty() && e.bounty > 0;
            case HOUSE_COINFLIP -> e.commission >= 0 && e.winChance >= 0 && e.winChance <= 1;
        };
    }

    private MonopolyEventEntry weightedPick(List<MonopolyEventEntry> candidates) {
        double total = candidates.stream().mapToDouble(e -> e.weight).sum();
        if (total <= 0) {
            return null;
        }
        double r = rng.get() * total;
        double acc = 0.0;
        for (MonopolyEventEntry e : candidates) {
            acc += e.weight;
            if (r <= acc) {
                return e;
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    private Double resolveMultiplier(MonopolyEventEntry e) {
        EventType type = EventType.fromId(e.type);
        if ((type == EventType.SALARY_MULTIPLIER || type == EventType.QUEST_REWARD_MULTIPLIER)
                && e.multipliers != null && !e.multipliers.isEmpty()) {
            int index = Math.min(e.multipliers.size() - 1, (int) (rng.get() * e.multipliers.size()));
            return e.multipliers.get(index);
        }
        return null;
    }

    private String resolveMob(MonopolyEventEntry e) {
        if (EventType.fromId(e.type) == EventType.MOB_WANTED && e.mobs != null && !e.mobs.isEmpty()) {
            int index = Math.min(e.mobs.size() - 1, (int) (rng.get() * e.mobs.size()));
            return e.mobs.get(index);
        }
        return null;
    }

    /**
     * Elige al azar uno de los mensajes configurados del evento; {@code null} si la lista está
     * vacía (entonces el broadcast usa el mensaje por defecto del tipo). Se guarda el template en
     * bruto: la sustitución de tokens ocurre en {@link #formatMessage} al anunciar.
     */
    private String resolveMessage(MonopolyEventEntry e) {
        if (e.messages == null || e.messages.isEmpty()) {
            return null;
        }
        int index = Math.min(e.messages.size() - 1, (int) (rng.get() * e.messages.size()));
        return e.messages.get(index);
    }

    private String formatMessage(MonopolyEventEntry e) {
        String msg = currentMessage == null ? defaultMessage(e) : currentMessage;
        if (currentMultiplier != null) {
            msg = msg.replace("%multiplier%", formatMultiplier(currentMultiplier));
        }
        if (currentMob != null) {
            msg = msg.replace("%mob%", friendlyMobName(currentMob));
        }
        msg = msg.replace("%bounty%", Money.format(wantedBounty()));
        msg = msg.replace("%commission%", String.format(java.util.Locale.US, "%.1f", houseCommission() * 100));
        return msg;
    }

    private static String defaultMessage(MonopolyEventEntry e) {
        EventType type = EventType.fromId(e.type);
        return switch (type) {
            case SALARY_MULTIPLIER -> "Evento salarial: los salarios se multiplican por %multiplier%.";
            case QUEST_REWARD_MULTIPLIER -> "Fiebre de misiones: las recompensas se multiplican por %multiplier%.";
            case MOB_WANTED -> "Se busca un mob: matar %mob% otorga %bounty% extra.";
            case HOUSE_COINFLIP -> "Cara o cruz contra La Casa: /monopoly coinflip <cantidad> [jugador].";
            case null -> "Evento de la economia.";
        };
    }

    private static String formatMultiplier(double multiplier) {
        return String.format(java.util.Locale.US, "x%.2f", multiplier);
    }

    private static String friendlyMobName(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return "un mob";
        }
        int colon = entityId.indexOf(':');
        return colon >= 0 ? entityId.substring(colon + 1) : entityId;
    }

    private void broadcast(MinecraftServer server, String msg) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(Component.literal("§6[Monopoly] §f" + msg));
        }
    }

    // ------------------------------------------------------------------
    // Cara o cruz contra La Casa y entre jugadores
    // ------------------------------------------------------------------

    /**
     * Apuesta de un jugador contra La Casa: paga {@code cantidad * (1 + comision)} (la comisión
     * se quema como sink), y con probabilidad {@link #winChance()} recibe el doble de la apuesta.
     * Con comisión {@code c}, la esperanza matemática es exactamente {@code -c * apuesta}.
     */
    public void coinflipVsHouse(MinecraftServer server, ServerPlayer player, double amount) {
        double rounded = Money.round(amount);
        if (!isCoinflipActive()) {
            player.sendSystemMessage(Component.literal("§c[Monopoly] §fNo hay ningún evento de cara o cruz activo."));
            return;
        }
        if (!validateBet(player, rounded)) {
            return;
        }

        double cost = Money.round(rounded * (1 + houseCommission()));
        if (!EconomyManager.get().take(player.getUUID(), cost)) {
            player.sendSystemMessage(Component.literal("§c[Monopoly] §fNo tienes saldo suficiente: apostar " + Money.format(rounded) + " cuesta " + Money.format(cost) + " con la comision de La Casa."));
            TransactionSounds.failure(player);
            return;
        }

        if (rng.get() < winChance()) {
            double payout = Money.round(rounded * 2);
            EconomyManager.get().give(player.getUUID(), payout);
            player.sendSystemMessage(Component.literal("§a[Monopoly] §f¡Ganaste el cara o cruz contra La Casa! Recibes " + Money.format(payout) + "."));
            TransactionSounds.success(player);
        } else {
            player.sendSystemMessage(Component.literal("§c[Monopoly] §fPerdiste el cara o cruz contra La Casa. Esta se queda con " + Money.format(cost) + "."));
            TransactionSounds.failure(player);
        }
    }

    /**
     * Reta a otro jugador a un cara o cruz: no mueve nada todavía, solo registra una invitación
     * pendiente (patrón de invitación, igual que /trade). El retado decide con /monopoly accept.
     */
    public void inviteCoinflip(MinecraftServer server, ServerPlayer challenger, ServerPlayer target, double amount) {
        double rounded = Money.round(amount);
        if (!isCoinflipActive()) {
            challenger.sendSystemMessage(Component.literal("§c[Monopoly] §fNo hay ningún evento de cara o cruz activo."));
            return;
        }
        if (challenger.getUUID().equals(target.getUUID())) {
            challenger.sendSystemMessage(Component.literal("§c[Monopoly] §fNo puedes retarte a ti mismo."));
            return;
        }
        if (!validateBet(challenger, rounded)) {
            return;
        }

        double cost = Money.round(rounded * (1 + houseCommission()));
        if (EconomyManager.get().getBalance(challenger.getUUID()) < cost) {
            challenger.sendSystemMessage(Component.literal("§c[Monopoly] §fNo tienes saldo suficiente: apostar " + Money.format(rounded) + " cuesta " + Money.format(cost) + " con la comision de La Casa."));
            TransactionSounds.failure(challenger);
            return;
        }

        pendingCoinflipInvites.put(target.getUUID().toString(),
                new CoinflipInvite(challenger.getUUID().toString(), rounded,
                        server.getTickCount() + COINFLIP_INVITE_TIMEOUT_TICKS));

        challenger.sendSystemMessage(Component.literal("§a[Monopoly] §fInvitacion de cara o cruz enviada a " + target.getGameProfile().getName() + " por " + Money.format(rounded) + "."));
        Component acceptButton = Component.literal("[Aceptar]")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/monopoly accept")));
        target.sendSystemMessage(Component.literal("§6[Monopoly] §f" + challenger.getGameProfile().getName() + " te reta a un cara o cruz por " + Money.format(rounded) + " (con la comision de La Casa incluida). ").append(acceptButton));
    }

    /**
     * El retado acepta su invitación pendiente: aquí es donde se ejecuta el cara o cruz. Ambos
     * pagan {@code cantidad * (1 + comision)} (las comisiones se queman) y el ganador recibe el
     * doble de la apuesta. Si alguno se quedó sin fondos, se anula sin mover nada.
     */
    public boolean acceptCoinflip(MinecraftServer server, ServerPlayer acceptor) {
        CoinflipInvite invite = pendingCoinflipInvites.get(acceptor.getUUID().toString());
        if (!isCoinflipActive()) {
            acceptor.sendSystemMessage(Component.literal("§c[Monopoly] §fEl evento de cara o cruz ya termino."));
            return false;
        }
        if (invite == null) {
            acceptor.sendSystemMessage(Component.literal("§c[Monopoly] §fNo tienes ninguna invitacion de cara o cruz pendiente."));
            return false;
        }

        ServerPlayer challenger = server.getPlayerList().getPlayer(UUID.fromString(invite.challengerUuid()));
        if (challenger == null) {
            pendingCoinflipInvites.remove(acceptor.getUUID().toString());
            acceptor.sendSystemMessage(Component.literal("§c[Monopoly] §fQuien te reto ya no esta conectado; la invitacion se anulo."));
            return false;
        }

        double cost = Money.round(invite.amount() * (1 + houseCommission()));
        if (EconomyManager.get().getBalance(challenger.getUUID()) < cost || EconomyManager.get().getBalance(acceptor.getUUID()) < cost) {
            acceptor.sendSystemMessage(Component.literal("§c[Monopoly] §fAlguno de los dos no tiene saldo suficiente para cubrir la apuesta; la invitacion sigue pendiente."));
            TransactionSounds.failure(acceptor);
            return false;
        }

        pendingCoinflipInvites.remove(acceptor.getUUID().toString());
        EconomyManager.get().take(challenger.getUUID(), cost);
        EconomyManager.get().take(acceptor.getUUID(), cost);

        boolean challengerWins = rng.get() < winChance();
        UUID winnerUuid = challengerWins ? challenger.getUUID() : acceptor.getUUID();
        double payout = Money.round(invite.amount() * 2);
        EconomyManager.get().give(winnerUuid, payout);

        String winnerName = winnerUuid.equals(challenger.getUUID())
                ? challenger.getGameProfile().getName()
                : acceptor.getGameProfile().getName();
        broadcast(server, "Cara o cruz entre " + challenger.getGameProfile().getName() + " y " + acceptor.getGameProfile().getName()
                + " por " + Money.format(invite.amount()) + ": gana §e" + winnerName + "§f (§e+" + Money.format(payout) + "§f).");
        return true;
    }

    /** El retado rechaza su invitación pendiente de cara o cruz. */
    public boolean denyCoinflip(MinecraftServer server, ServerPlayer denier) {
        CoinflipInvite invite = pendingCoinflipInvites.remove(denier.getUUID().toString());
        if (invite == null) {
            denier.sendSystemMessage(Component.literal("§c[Monopoly] §fNo tienes ninguna invitacion de cara o cruz pendiente."));
            return false;
        }
        ServerPlayer challenger = server.getPlayerList().getPlayer(UUID.fromString(invite.challengerUuid()));
        if (challenger != null) {
            challenger.sendSystemMessage(Component.literal("§c[Monopoly] §f" + denier.getGameProfile().getName() + " rechazo tu reto de cara o cruz."));
        }
        return true;
    }

    private void expireCoinflipInvites(MinecraftServer server) {
        long now = server.getTickCount();
        pendingCoinflipInvites.entrySet().removeIf(entry -> entry.getValue().expiresAtTick() <= now);
    }

    private boolean validateBet(ServerPlayer player, double amount) {
        MonopolyConfig config = ConfigManager.monopoly();
        if (amount < Math.max(0.01, config.minBet)) {
            player.sendSystemMessage(Component.literal("§c[Monopoly] §fLa apuesta minima es " + Money.format(Math.max(0.01, config.minBet)) + "."));
            TransactionSounds.failure(player);
            return false;
        }
        if (config.maxBet > 0 && amount > config.maxBet) {
            player.sendSystemMessage(Component.literal("§c[Monopoly] §fLa apuesta maxima es " + Money.format(config.maxBet) + "."));
            TransactionSounds.failure(player);
            return false;
        }
        return true;
    }
}
