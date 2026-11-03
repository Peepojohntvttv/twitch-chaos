package dev.twitchchaos.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.twitchchaos.TwitchChaosMod;
import dev.twitchchaos.core.ChaosEvents;
import dev.twitchchaos.twitch.TwitchEvent;
import dev.twitchchaos.util.Perm;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-Seite der Menue-Kommunikation. Jede Aktion wird auf Berechtigung geprueft; es gibt nur feste Aktionen. */
public final class ServerNet {
    private static final Logger LOG = LoggerFactory.getLogger("TwitchChaos/Net");

    private ServerNet() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ActionPayload.TYPE, ActionPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionPayload.TYPE,
                (payload, context) -> handle(context.player(), payload));
    }

    // ------------------------------------------------------------ Zustand senden

    public static String buildState(ServerPlayer viewer) {
        JsonObject root = new JsonObject();
        root.add("config", TwitchChaosMod.config.toJson());

        JsonObject tw = new JsonObject();
        if (TwitchChaosMod.twitch != null) {
            tw.addProperty("state", TwitchChaosMod.twitch.state().name());
            tw.addProperty("status", TwitchChaosMod.twitch.status());
            tw.addProperty("deviceUrl", TwitchChaosMod.twitch.deviceUrl());
            tw.addProperty("userCode", TwitchChaosMod.twitch.userCode());
            tw.addProperty("login", TwitchChaosMod.twitch.login());
        } else {
            tw.addProperty("state", "DISCONNECTED");
            tw.addProperty("status", "Nicht verbunden");
        }
        root.add("twitch", tw);

        root.addProperty("standalone", TwitchChaosMod.manager != null && TwitchChaosMod.manager.standalone());
        root.addProperty("queue", TwitchChaosMod.manager == null ? 0 : TwitchChaosMod.manager.queueSize());
        root.addProperty("admin", Perm.canAdmin(viewer.createCommandSourceStack()));

        JsonArray events = new JsonArray();
        for (ChaosEvents.Def d : ChaosEvents.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", d.id());
            o.addProperty("name", d.name());
            o.addProperty("rarity", d.rarity().label);
            events.add(o);
        }
        root.add("events", events);
        return root.toString();
    }

    public static void sendState(ServerPlayer player, boolean open) {
        if (!ServerPlayNetworking.canSend(player, StatePayload.TYPE)) return;
        ServerPlayNetworking.send(player, new StatePayload(open, buildState(player)));
    }

    /** Sendet den Zustand an alle Spieler (nur sinnvoll fuer offene Menues; sehr kleine Pakete). */
    public static void broadcastState(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) sendState(p, false);
    }

    // ------------------------------------------------------------ Aktionen

    private static void handle(ServerPlayer player, ActionPayload p) {
        MinecraftServer server = TwitchChaosMod.server;
        if (server == null || TwitchChaosMod.manager == null) return;

        if (p.action().equals("refresh")) {
            sendState(player, false);
            return;
        }
        if (!Perm.canAdmin(player.createCommandSourceStack())) {
            player.sendSystemMessage(Component.literal("[Twitch Chaos] Dir fehlt die Berechtigung fuer diese Aktion."));
            return;
        }

        String arg = p.arg() == null ? "" : p.arg();
        if (arg.length() > 200) return;

        try {
            switch (p.action()) {
                case "toggle" -> TwitchChaosMod.config.toggle(arg);
                case "adjust" -> {
                    String[] parts = arg.split(":");
                    if (parts.length == 2) TwitchChaosMod.config.adjust(parts[0], Integer.parseInt(parts[1]));
                }
                case "set" -> {
                    int i = arg.indexOf('=');
                    if (i > 0) TwitchChaosMod.config.setString(arg.substring(0, i), arg.substring(i + 1));
                }
                case "connect" -> {
                    if (TwitchChaosMod.twitch != null) {
                        TwitchChaosMod.config.sanitize();
                        TwitchChaosMod.twitch.connect();
                    }
                }
                case "disconnect" -> {
                    if (TwitchChaosMod.twitch != null) TwitchChaosMod.twitch.disconnect();
                }
                case "logout" -> {
                    if (TwitchChaosMod.twitch != null) TwitchChaosMod.twitch.logout();
                }
                case "standalone" -> TwitchChaosMod.manager.setStandalone(arg.equals("on"));
                case "event" -> {
                    if (!TwitchChaosMod.manager.runById(arg, player, player.getScoreboardName())) {
                        player.sendSystemMessage(Component.literal("[Twitch Chaos] Unbekanntes Event: " + arg));
                    }
                }
                case "test" -> {
                    TwitchEvent ev = testEvent(arg);
                    if (ev != null) TwitchChaosMod.manager.test(ev);
                }
                default -> LOG.debug("Unbekannte Aktion: {}", p.action());
            }
        } catch (Exception e) {
            LOG.warn("Aktion '{}' fehlgeschlagen: {}", p.action(), e.toString());
        }
        broadcastState(server);
    }

    public static TwitchEvent testEvent(String type) {
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "follow" -> new TwitchEvent(TwitchEvent.Type.FOLLOW, "TestUser", 0, 0, 0);
            case "sub" -> new TwitchEvent(TwitchEvent.Type.SUB, "TestUser", 0, 1, 0);
            case "resub" -> new TwitchEvent(TwitchEvent.Type.RESUB, "TestUser", 0, 1, 6);
            case "gift" -> new TwitchEvent(TwitchEvent.Type.GIFT, "TestUser", 1, 1, 0);
            case "bits" -> new TwitchEvent(TwitchEvent.Type.BITS, "TestUser", 500, 0, 0);
            case "raid" -> new TwitchEvent(TwitchEvent.Type.RAID, "TestRaid", 30, 0, 0);
            case "points" -> new TwitchEvent(TwitchEvent.Type.POINTS, "TestUser", 0, 0, 0);
            default -> null;
        };
    }
}
