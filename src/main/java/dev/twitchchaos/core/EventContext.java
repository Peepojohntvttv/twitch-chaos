package dev.twitchchaos.core;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.twitchchaos.config.ChaosConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Alles, was ein Chaos-Event braucht. Events werden ueber feste, vom Mod selbst gebaute
 * Minecraft-Befehle ausgefuehrt (summon, effect, give ...). In diese Befehle fliessen NIEMALS
 * Twitch-Texte ein - nur Zahlen und der (validierte) Spielername des Ziels.
 */
public final class EventContext {
    private static final Logger LOG = LoggerFactory.getLogger("TwitchChaos/Event");

    public final MinecraftServer server;
    public final ServerPlayer player;
    public final ChaosConfig cfg;
    /** Staerke des Ausloesers (Bits, Zuschauer bei Raids, ...), 0 wenn nicht vorhanden. */
    public final int power;
    /** Selektor fuer das Ziel: Spielername (nur [A-Za-z0-9_]) oder "@p" als Rueckfall. */
    public final String target;

    private final Scheduler scheduler;
    private final ServerLevel startLevel;

    public EventContext(MinecraftServer server, ServerPlayer player, ChaosConfig cfg, Scheduler scheduler, int power) {
        this.server = server;
        this.player = player;
        this.cfg = cfg;
        this.scheduler = scheduler;
        this.power = power;
        this.startLevel = (ServerLevel) player.level();
        String name = player.getScoreboardName();
        this.target = name != null && name.matches("[A-Za-z0-9_]{1,16}") ? name : "@p";
    }

    public ThreadLocalRandom rnd() {
        return ThreadLocalRandom.current();
    }

    public boolean alive() {
        return !player.isRemoved() && player.isAlive();
    }

    public int mobs(int base) {
        return Math.max(1, Math.min(base, cfg.maxMobsPerEvent));
    }

    public void later(int ticks, Runnable r) {
        scheduler.schedule(ticks, r);
    }

    // ------------------------------------------------------------ Befehle

    /** Fuehrt einen internen Befehl an der aktuellen Position des Spielers aus. Gibt das Ergebnis zurueck (0 = Fehler). */
    public int run(String command) {
        if (!alive()) return 0;
        return exec((ServerLevel) player.level(), player.position(), command);
    }

    /** Wie run(), aber mit Ersatzbefehl, falls der erste fehlschlaegt (z. B. wegen Syntaxaenderungen). */
    public boolean runOrElse(String command, String fallback) {
        if (run(command) > 0) return true;
        LOG.warn("Befehl fehlgeschlagen, nutze Ersatz: {}", command);
        return run(fallback) > 0;
    }

    /** Befehl mit absoluten Koordinaten in der Dimension, in der das Event gestartet wurde (auch nach Spielerwechsel). */
    public int runFixed(String command) {
        BlockPos p = player.blockPosition();
        return exec(startLevel, net.minecraft.world.phys.Vec3.atCenterOf(p), command);
    }

    private int exec(ServerLevel level, net.minecraft.world.phys.Vec3 pos, String command) {
        try {
            CommandSourceStack src = server.createCommandSourceStack()
                    .withLevel(level)
                    .withPosition(pos)
                    .withSuppressedOutput();
            return server.getCommands().getDispatcher().execute(command, src);
        } catch (CommandSyntaxException e) {
            LOG.warn("Interner Befehl ungueltig '{}': {}", command, e.getMessage());
            return 0;
        } catch (Exception e) {
            LOG.warn("Interner Befehl fehlgeschlagen '{}': {}", command, e.toString());
            return 0;
        }
    }

    // ------------------------------------------------------------ Hilfen

    /** Zufaelliger Versatz im Ring [min,max] Bloecke um den Spieler, als "~dx ~ ~dz". */
    public String ring(double min, double max) {
        double angle = rnd().nextDouble(0, Math.PI * 2);
        double r = min + rnd().nextDouble() * (max - min);
        return String.format(Locale.ROOT, "~%.1f ~ ~%.1f", Math.cos(angle) * r, Math.sin(angle) * r);
    }

    public String ringHigh(double min, double max, int height) {
        double angle = rnd().nextDouble(0, Math.PI * 2);
        double r = min + rnd().nextDouble() * (max - min);
        return String.format(Locale.ROOT, "~%.1f ~%d ~%.1f", Math.cos(angle) * r, height, Math.sin(angle) * r);
    }

    /** Beschwoert ein Wesen im Ring um den Spieler. nbt darf leer sein. */
    public boolean spawn(String entity, String nbt) {
        String cmd = "summon minecraft:" + entity + " " + ring(3, 7) + (nbt == null || nbt.isEmpty() ? "" : " " + nbt);
        return run(cmd) > 0;
    }

    /** Beschwoert mit NBT; bei Fehler ohne NBT, aber mit schlichtem Namen. */
    public void spawnNamed(String entity, String name, String nbt) {
        if (!spawn(entity, nbt)) {
            spawn(entity, "{CustomName:\"" + name + "\",CustomNameVisible:1b}");
        }
    }

    public void spawnMany(String entity, int count) {
        for (int i = 0; i < count; i++) spawn(entity, "");
    }

    /** Beschwoert count Wesen gestaffelt ueber die Zeit (delayTicks Abstand). */
    public void spawnOverTime(String[] types, int count, int delayTicks) {
        for (int i = 0; i < count; i++) {
            String type = types[rnd().nextInt(types.length)];
            later(i * delayTicks, () -> spawn(type, ""));
        }
    }

    public static String named(String name) {
        return "CustomName:\"" + name + "\",CustomNameVisible:1b";
    }
}
