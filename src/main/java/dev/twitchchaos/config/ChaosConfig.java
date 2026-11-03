package dev.twitchchaos.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistente Einstellungen (config/twitchchaos.json). Enthaelt KEINE Tokens - die liegen
 * getrennt in twitchchaos_auth.json. Alle Aenderungen laufen ueber eine feste Whitelist von Schluesseln.
 */
public final class ChaosConfig {
    private static final Logger LOG = LoggerFactory.getLogger("TwitchChaos/Config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Twitch
    public String channel = "";
    public String clientId = "";
    public boolean autoConnect = false;

    // Ereignis-Schalter
    public boolean enableFollow = true;
    public boolean enableSub = true;
    public boolean enableResub = true;
    public boolean enableGift = true;
    public boolean enableBits = true;
    public boolean enableRaid = true;
    public boolean enableChannelPoints = false;

    // Werte
    public int minBits = 100;
    public int eventChancePercent = 100;
    public int weightNormal = 70;
    public int weightRare = 22;
    public int weightSpecial = 8;
    public int cooldownSeconds = 8;
    public int specialCooldownSeconds = 60;
    public int maxMobsPerEvent = 40;
    public int maxQueue = 25;
    public int standaloneIntervalSeconds = 60;
    public String targetPlayer = "";

    private transient Path file;

    public static ChaosConfig load(Path file) {
        ChaosConfig cfg = null;
        if (Files.exists(file)) {
            try {
                cfg = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), ChaosConfig.class);
            } catch (Exception e) {
                LOG.error("Konfiguration konnte nicht gelesen werden, Standardwerte werden genutzt: {}", e.toString());
            }
        }
        if (cfg == null) cfg = new ChaosConfig();
        cfg.file = file;
        cfg.sanitize();
        cfg.save();
        return cfg;
    }

    /** Laedt die Datei erneut und uebernimmt alle Werte in dieses Objekt (fuer /twitchchaos reload). */
    public void reload() {
        ChaosConfig fresh = load(file);
        this.channel = fresh.channel;
        this.clientId = fresh.clientId;
        this.autoConnect = fresh.autoConnect;
        this.enableFollow = fresh.enableFollow;
        this.enableSub = fresh.enableSub;
        this.enableResub = fresh.enableResub;
        this.enableGift = fresh.enableGift;
        this.enableBits = fresh.enableBits;
        this.enableRaid = fresh.enableRaid;
        this.enableChannelPoints = fresh.enableChannelPoints;
        this.minBits = fresh.minBits;
        this.eventChancePercent = fresh.eventChancePercent;
        this.weightNormal = fresh.weightNormal;
        this.weightRare = fresh.weightRare;
        this.weightSpecial = fresh.weightSpecial;
        this.cooldownSeconds = fresh.cooldownSeconds;
        this.specialCooldownSeconds = fresh.specialCooldownSeconds;
        this.maxMobsPerEvent = fresh.maxMobsPerEvent;
        this.maxQueue = fresh.maxQueue;
        this.standaloneIntervalSeconds = fresh.standaloneIntervalSeconds;
        this.targetPlayer = fresh.targetPlayer;
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Konfiguration konnte nicht gespeichert werden: {}", e.toString());
        }
    }

    public void sanitize() {
        channel = cleanName(channel);
        clientId = clientId == null ? "" : clientId.trim().replaceAll("[^A-Za-z0-9]", "");
        targetPlayer = targetPlayer == null ? "" : targetPlayer.trim().replaceAll("[^A-Za-z0-9_]", "");
        minBits = clamp(minBits, 1, 1_000_000);
        eventChancePercent = clamp(eventChancePercent, 0, 100);
        weightNormal = clamp(weightNormal, 0, 100);
        weightRare = clamp(weightRare, 0, 100);
        weightSpecial = clamp(weightSpecial, 0, 100);
        if (weightNormal + weightRare + weightSpecial == 0) weightNormal = 1;
        cooldownSeconds = clamp(cooldownSeconds, 0, 3600);
        specialCooldownSeconds = clamp(specialCooldownSeconds, 0, 3600);
        maxMobsPerEvent = clamp(maxMobsPerEvent, 1, 100);
        maxQueue = clamp(maxQueue, 1, 200);
        standaloneIntervalSeconds = clamp(standaloneIntervalSeconds, 5, 3600);
    }

    private static String cleanName(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", "");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Schaltet einen Boolean-Schalter um. Gibt false zurueck, wenn der Schluessel unbekannt ist. */
    public boolean toggle(String key) {
        switch (key) {
            case "follow" -> enableFollow = !enableFollow;
            case "sub" -> enableSub = !enableSub;
            case "resub" -> enableResub = !enableResub;
            case "gift" -> enableGift = !enableGift;
            case "bits" -> enableBits = !enableBits;
            case "raid" -> enableRaid = !enableRaid;
            case "points" -> enableChannelPoints = !enableChannelPoints;
            case "autoconnect" -> autoConnect = !autoConnect;
            default -> {
                return false;
            }
        }
        save();
        return true;
    }

    /** Aendert einen Zahlenwert um delta (mit Begrenzung). */
    public boolean adjust(String key, int delta) {
        switch (key) {
            case "minBits" -> minBits += delta;
            case "chance" -> eventChancePercent += delta;
            case "wNormal" -> weightNormal += delta;
            case "wRare" -> weightRare += delta;
            case "wSpecial" -> weightSpecial += delta;
            case "cooldown" -> cooldownSeconds += delta;
            case "specialCooldown" -> specialCooldownSeconds += delta;
            case "maxMobs" -> maxMobsPerEvent += delta;
            case "interval" -> standaloneIntervalSeconds += delta;
            default -> {
                return false;
            }
        }
        sanitize();
        save();
        return true;
    }

    /** Setzt einen Textwert. Eingaben werden bereinigt (nur erlaubte Zeichen). */
    public boolean setString(String key, String value) {
        switch (key) {
            case "channel" -> channel = value;
            case "clientId" -> clientId = value;
            case "targetPlayer" -> targetPlayer = value;
            default -> {
                return false;
            }
        }
        sanitize();
        save();
        return true;
    }

    public JsonObject toJson() {
        return GSON.toJsonTree(this).getAsJsonObject();
    }
}
