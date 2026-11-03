package dev.twitchchaos.core;

import dev.twitchchaos.config.ChaosConfig;
import dev.twitchchaos.twitch.TwitchEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Serverseitige Steuerung: Warteschlange, Cooldowns, Wahrscheinlichkeiten, Zielauswahl.
 * Alle Methoden laufen auf dem Server-Thread.
 */
public final class ChaosManager {
    private static final Logger LOG = LoggerFactory.getLogger("TwitchChaos/Manager");

    private final MinecraftServer server;
    private final ChaosConfig cfg;
    private final Scheduler scheduler = new Scheduler();
    private final Deque<TwitchEvent> queue = new ArrayDeque<>();
    private long cooldownUntil;
    private boolean standalone;
    private long nextStandalone;

    public ChaosManager(MinecraftServer server, ChaosConfig cfg) {
        this.server = server;
        this.cfg = cfg;
    }

    public boolean standalone() {
        return standalone;
    }

    public int queueSize() {
        return queue.size();
    }

    public void setStandalone(boolean on) {
        standalone = on;
        nextStandalone = scheduler.now() + 20L * 3;
    }

    // ------------------------------------------------------------ Tick

    public void tick() {
        scheduler.tick();
        long now = scheduler.now();

        if (standalone && now >= nextStandalone && hasPlayers()) {
            nextStandalone = now + 20L * cfg.standaloneIntervalSeconds;
            ChaosEvents.Def def = ChaosEvents.pickWeighted(ThreadLocalRandom.current(),
                    cfg.weightNormal, cfg.weightRare, cfg.weightSpecial);
            execute(def, pickTarget(), "Chaos-Modus", 0);
        }

        if (!queue.isEmpty() && now >= cooldownUntil && hasPlayers()) {
            TwitchEvent ev = queue.pollFirst();
            handle(ev);
        }
    }

    private boolean hasPlayers() {
        return !server.getPlayerList().getPlayers().isEmpty();
    }

    // ------------------------------------------------------------ Twitch-Eingang

    /** Nimmt ein Twitch-Ereignis an (Schalter, Mindest-Bits, Chance, Warteschlange). */
    public void submit(TwitchEvent ev) {
        if (!enabled(ev.type())) return;
        if (ev.type() == TwitchEvent.Type.BITS && ev.amount() < cfg.minBits) return;
        if (ThreadLocalRandom.current().nextInt(100) >= cfg.eventChancePercent) return;
        if (queue.size() >= cfg.maxQueue) {
            LOG.warn("Warteschlange voll - Ereignis von {} verworfen.", ev.user());
            return;
        }
        queue.addLast(ev);
    }

    private boolean enabled(TwitchEvent.Type t) {
        return switch (t) {
            case FOLLOW -> cfg.enableFollow;
            case SUB -> cfg.enableSub;
            case RESUB -> cfg.enableResub;
            case GIFT -> cfg.enableGift;
            case BITS -> cfg.enableBits;
            case RAID -> cfg.enableRaid;
            case POINTS -> cfg.enableChannelPoints;
        };
    }

    /** Fuehrt ein Test-Ereignis sofort aus (ohne Chance/Cooldown/Warteschlange). */
    public boolean test(TwitchEvent ev) {
        if (!hasPlayers()) return false;
        handle(ev);
        return true;
    }

    private void handle(TwitchEvent ev) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        ChaosEvents.Def def = switch (ev.type()) {
            case FOLLOW, POINTS -> ChaosEvents.pickWeighted(r, cfg.weightNormal, cfg.weightRare, cfg.weightSpecial);
            case SUB -> switch (ev.tier()) {
                case 3 -> ChaosEvents.get("boss");
                case 2 -> ChaosEvents.get("miniboss");
                default -> r.nextBoolean() ? ChaosEvents.get("mimic") : ChaosEvents.get("miniboss");
            };
            case RESUB -> ev.months() >= 12
                    ? ChaosEvents.pick(r, Rarity.SPECIAL)
                    : ChaosEvents.pick(r, Rarity.RARE);
            case GIFT -> ev.amount() >= 5 ? ChaosEvents.get("jackpot")
                    : (r.nextBoolean() ? ChaosEvents.get("reward") : ChaosEvents.get("danger"));
            case BITS -> ev.amount() >= 5000 ? ChaosEvents.get("boss")
                    : ev.amount() >= 1000 ? ChaosEvents.pick(r, Rarity.SPECIAL)
                    : ev.amount() >= 500 ? ChaosEvents.pick(r, Rarity.RARE)
                    : ChaosEvents.pick(r, Rarity.NORMAL);
            case RAID -> ChaosEvents.get("wave");
        };
        String cause = ev.user() + " (" + label(ev) + ")";
        int power = ev.type() == TwitchEvent.Type.RAID || ev.type() == TwitchEvent.Type.BITS ? ev.amount() : 0;
        execute(def, pickTarget(), cause, power);
    }

    private static String label(TwitchEvent ev) {
        return switch (ev.type()) {
            case FOLLOW -> "Follow";
            case SUB -> "Sub Stufe " + ev.tier();
            case RESUB -> "Resub " + ev.months() + " Monate";
            case GIFT -> ev.amount() + " Gift-Subs";
            case BITS -> ev.amount() + " Bits";
            case RAID -> "Raid mit " + ev.amount() + " Zuschauern";
            case POINTS -> "Kanalpunkte";
        };
    }

    // ------------------------------------------------------------ Ausfuehrung

    /** Manuell ausloesen (Befehl/Menue): ohne Chance, ohne Cooldown. */
    public boolean runById(String id, ServerPlayer target, String cause) {
        ChaosEvents.Def def = id.equalsIgnoreCase("random")
                ? ChaosEvents.pickWeighted(ThreadLocalRandom.current(), cfg.weightNormal, cfg.weightRare, cfg.weightSpecial)
                : ChaosEvents.get(id);
        if (def == null) return false;
        ServerPlayer p = target != null ? target : pickTarget();
        if (p == null) return false;
        execute(def, p, cause, 0);
        return true;
    }

    private void execute(ChaosEvents.Def def, ServerPlayer target, String cause, int power) {
        if (target == null) {
            LOG.info("Kein Spieler online - Event '{}' uebersprungen.", def.id());
            return;
        }
        EventContext ctx = new EventContext(server, target, cfg, scheduler, power);
        announce(cause, def);
        try {
            def.action().run(ctx);
        } catch (Exception e) {
            LOG.error("Event '{}' ist fehlgeschlagen: {}", def.id(), e.toString());
        }
        int cd = def.rarity() == Rarity.SPECIAL ? cfg.specialCooldownSeconds : cfg.cooldownSeconds;
        cooldownUntil = scheduler.now() + 20L * cd;
    }

    private void announce(String cause, ChaosEvents.Def def) {
        ChatFormatting color = switch (def.rarity()) {
            case NORMAL -> ChatFormatting.WHITE;
            case RARE -> ChatFormatting.LIGHT_PURPLE;
            case SPECIAL -> ChatFormatting.GOLD;
        };
        Component msg = Component.literal("[Twitch Chaos] ").withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.BOLD)
                .append(Component.literal(cause + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(def.name()).withStyle(color));
        server.getPlayerList().broadcastSystemMessage(msg, false);
    }

    private ServerPlayer pickTarget() {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return null;
        if (cfg.targetPlayer != null && !cfg.targetPlayer.isBlank()) {
            ServerPlayer p = server.getPlayerList().getPlayerByName(cfg.targetPlayer);
            if (p != null) return p;
        }
        return players.get(ThreadLocalRandom.current().nextInt(players.size()));
    }
}
