package dev.twitchchaos.twitch;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.twitchchaos.config.ChaosConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Eigenstaendige Twitch-EventSub-Anbindung ueber WebSocket (wss://eventsub.wss.twitch.tv/ws).
 * - baut die Verbindung auf und abonniert die Ereignisse,
 * - erkennt Ausfaelle (Keepalive-Ueberwachung) und verbindet automatisch neu (Backoff 2-60 s),
 * - folgt "session_reconnect"-Aufforderungen von Twitch,
 * - filtert doppelte Nachrichten anhand der message_id.
 * Diese Klasse kennt Minecraft nicht; Ereignisse werden an den uebergebenen Consumer gegeben
 * (dieser muss selbst auf den Server-Thread wechseln).
 */
public final class TwitchService {
    private static final Logger LOG = LoggerFactory.getLogger("TwitchChaos/Twitch");
    private static final String WS_URL = "wss://eventsub.wss.twitch.tv/ws";
    private static final String SUBS_URL = "https://api.twitch.tv/helix/eventsub/subscriptions";

    public enum State {DISCONNECTED, AUTHORIZING, CONNECTING, CONNECTED, ERROR}

    /** Fataler Fehler: kein automatischer Neuversuch (z. B. fehlende Client-ID). */
    private static final class FatalException extends Exception {
        FatalException(String msg) {
            super(msg);
        }
    }

    private final ChaosConfig cfg;
    private final TwitchAuth auth;
    private final Consumer<TwitchEvent> sink;
    private final Runnable onChange;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TwitchChaos-Watchdog");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> seenMessages = new LinkedHashSet<>();

    private volatile State state = State.DISCONNECTED;
    private volatile String status = "Nicht verbunden";
    private volatile String deviceUrl = "";
    private volatile String userCode = "";
    private volatile boolean wanted;
    private volatile Thread loopThread;
    private volatile Conn current;
    private volatile CompletableFuture<Void> sessionEnded = new CompletableFuture<>();
    private volatile String resumeUrl;
    private int watchdogTicks;

    public TwitchService(ChaosConfig cfg, TwitchAuth auth, Consumer<TwitchEvent> sink, Runnable onChange) {
        this.cfg = cfg;
        this.auth = auth;
        this.sink = sink;
        this.onChange = onChange;
        scheduler.scheduleWithFixedDelay(this::watchdog, 5, 5, TimeUnit.SECONDS);
    }

    // ---------------------------------------------------------------- oeffentliche API

    public State state() {
        return state;
    }

    public String status() {
        return status;
    }

    public String deviceUrl() {
        return deviceUrl;
    }

    public String userCode() {
        return userCode;
    }

    public String login() {
        return auth.login();
    }

    public synchronized void connect() {
        if (wanted) return;
        wanted = true;
        Thread t = new Thread(this::runLoop, "TwitchChaos-Connection");
        t.setDaemon(true);
        loopThread = t;
        t.start();
    }

    public synchronized void disconnect() {
        wanted = false;
        Thread t = loopThread;
        if (t != null) t.interrupt();
        Conn c = current;
        if (c != null) c.close();
        CompletableFuture<Void> f = sessionEnded;
        if (f != null) f.complete(null);
        deviceUrl = "";
        userCode = "";
        setState(State.DISCONNECTED, "Nicht verbunden");
    }

    /** Trennt und loescht das gespeicherte Token. */
    public void logout() {
        disconnect();
        auth.logout();
        setState(State.DISCONNECTED, "Abgemeldet - gespeicherte Anmeldung geloescht");
    }

    public void shutdown() {
        disconnect();
        scheduler.shutdownNow();
    }

    // ---------------------------------------------------------------- Hauptschleife

    private void runLoop() {
        int backoff = 2;
        try {
            while (wanted) {
                try {
                    setState(State.CONNECTING, "Verbinde mit Twitch ...");
                    ensureAuthorized();
                    if (!wanted) break;
                    String broadcasterId = resolveBroadcasterId();

                    String url = resumeUrl != null ? resumeUrl : WS_URL;
                    boolean resume = resumeUrl != null;
                    resumeUrl = null;
                    sessionEnded = new CompletableFuture<>();
                    Conn c = new Conn(url, resume, null, broadcasterId);
                    current = c;
                    c.open();
                    sessionEnded.get();
                    if (c.welcomed) backoff = 2;
                } catch (InterruptedException e) {
                    break;
                } catch (FatalException e) {
                    wanted = false;
                    setState(State.ERROR, e.getMessage());
                    return;
                } catch (Exception e) {
                    LOG.warn("Twitch-Verbindungsfehler: {}", e.toString());
                    setState(State.CONNECTING, "Fehler: " + shorten(e.getMessage()));
                }
                if (!wanted) break;
                setState(State.CONNECTING, "Verbindung getrennt - neuer Versuch in " + backoff + " s");
                try {
                    Thread.sleep(backoff * 1000L);
                } catch (InterruptedException e) {
                    break;
                }
                backoff = Math.min(60, backoff * 2);
            }
        } finally {
            Conn c = current;
            if (c != null) c.close();
            if (state != State.ERROR) setState(State.DISCONNECTED, "Nicht verbunden");
        }
    }

    private void ensureAuthorized() throws Exception {
        String cid = cfg.clientId;
        if (cid == null || cid.isBlank()) {
            throw new FatalException("Client-ID fehlt. Trage sie im Menue unter 'Verbindung' ein.");
        }
        if (auth.hasToken() && auth.clientId().equals(cid)) {
            if (auth.validate() || (auth.refresh() && auth.validate())) return;
        }
        setState(State.AUTHORIZING, "Anmeldung bei Twitch ...");
        TwitchAuth.DeviceCode dc = auth.requestDeviceCode(cid);
        deviceUrl = dc.verificationUri();
        userCode = dc.userCode();
        setState(State.AUTHORIZING, "Oeffne " + deviceUrl + " und gib den Code " + userCode + " ein");
        boolean ok = auth.pollForToken(cid, dc, () -> !wanted);
        deviceUrl = "";
        userCode = "";
        if (!wanted) return;
        if (!ok) throw new FatalException("Anmeldung abgelaufen oder abgebrochen.");
        if (!auth.validate()) throw new FatalException("Twitch hat das neue Token nicht akzeptiert.");
    }

    private String resolveBroadcasterId() throws Exception {
        String channel = cfg.channel == null ? "" : cfg.channel;
        if (channel.isBlank() || channel.equalsIgnoreCase(auth.login())) return auth.userId();
        TwitchAuth.HttpResult r = auth.helix("GET", "https://api.twitch.tv/helix/users?login=" + channel, null);
        if (r.status() == 200 && r.json().has("data") && r.json().getAsJsonArray("data").size() > 0) {
            return r.json().getAsJsonArray("data").get(0).getAsJsonObject().get("id").getAsString();
        }
        throw new FatalException("Twitch-Kanal '" + channel + "' wurde nicht gefunden.");
    }

    // ---------------------------------------------------------------- Watchdog

    private void watchdog() {
        try {
            Conn c = current;
            if (c != null && wanted) {
                long now = System.currentTimeMillis();
                long limit = c.welcomed ? (c.keepalive + 10L) * 1000L : 20_000L;
                long ref = c.welcomed ? c.lastMessage : c.openedAt;
                if (now - ref > limit) {
                    LOG.warn("Keine Nachricht von Twitch - Verbindung wird neu aufgebaut.");
                    c.close();
                    onClosed(c, "Zeitueberschreitung");
                }
            }
            if (++watchdogTicks >= 720) { // ca. stuendlich
                watchdogTicks = 0;
                if (state == State.CONNECTED && !auth.validate() && !(auth.refresh() && auth.validate())) {
                    wanted = false;
                    setState(State.ERROR, "Twitch-Anmeldung ungueltig - bitte neu verbinden.");
                    disconnectQuietly();
                }
            }
        } catch (Exception e) {
            LOG.warn("Watchdog-Fehler: {}", e.toString());
        }
    }

    private void disconnectQuietly() {
        Conn c = current;
        if (c != null) c.close();
        CompletableFuture<Void> f = sessionEnded;
        if (f != null) f.complete(null);
    }

    private void onClosed(Conn c, String reason) {
        if (c != current) return; // ersetzte/alte Verbindung
        LOG.info("Twitch-Verbindung beendet: {}", reason);
        CompletableFuture<Void> f = sessionEnded;
        if (f != null) f.complete(null);
    }

    private void setState(State s, String text) {
        this.state = s;
        this.status = text;
        try {
            onChange.run();
        } catch (Exception e) {
            LOG.debug("Status-Callback-Fehler: {}", e.toString());
        }
    }

    private static String shorten(String s) {
        if (s == null) return "unbekannt";
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }

    // ---------------------------------------------------------------- WebSocket-Verbindung

    private final class Conn implements WebSocket.Listener {
        final String url;
        final boolean resume;
        final Conn replaced;
        final String broadcasterId;
        volatile WebSocket ws;
        volatile long lastMessage = System.currentTimeMillis();
        final long openedAt = System.currentTimeMillis();
        volatile int keepalive = 10;
        volatile boolean welcomed;
        private final StringBuilder buffer = new StringBuilder();

        Conn(String url, boolean resume, Conn replaced, String broadcasterId) {
            this.url = url;
            this.resume = resume;
            this.replaced = replaced;
            this.broadcasterId = broadcasterId;
        }

        void open() {
            http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(URI.create(url), this)
                    .whenComplete((w, t) -> {
                        if (t != null) {
                            LOG.warn("WebSocket konnte nicht geoeffnet werden: {}", t.toString());
                            onClosed(this, String.valueOf(t));
                        }
                    });
        }

        void close() {
            WebSocket w = ws;
            if (w != null) {
                try {
                    w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
                } catch (Exception ignored) {
                    // egal
                }
                try {
                    w.abort();
                } catch (Exception ignored) {
                    // egal
                }
            }
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            this.ws = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String msg = buffer.toString();
                buffer.setLength(0);
                lastMessage = System.currentTimeMillis();
                try {
                    handle(msg);
                } catch (Exception e) {
                    LOG.warn("Fehler beim Verarbeiten einer Twitch-Nachricht: {}", e.toString());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            onClosed(this, "Server schloss die Verbindung (" + statusCode + ")");
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            onClosed(this, "Fehler: " + error);
        }

        private void handle(String raw) {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject meta = root.getAsJsonObject("metadata");
            JsonObject payload = root.has("payload") && root.get("payload").isJsonObject()
                    ? root.getAsJsonObject("payload") : new JsonObject();
            String type = TwitchAuth.str(meta, "message_type");
            String messageId = TwitchAuth.str(meta, "message_id");

            if (!messageId.isEmpty() && isDuplicate(messageId)) return;

            switch (type) {
                case "session_welcome" -> {
                    JsonObject session = payload.getAsJsonObject("session");
                    String sessionId = TwitchAuth.str(session, "id");
                    if (session.has("keepalive_timeout_seconds") && !session.get("keepalive_timeout_seconds").isJsonNull()) {
                        keepalive = session.get("keepalive_timeout_seconds").getAsInt();
                    }
                    welcomed = true;
                    if (replaced != null) replaced.close();
                    if (resume) {
                        setState(State.CONNECTED, "Verbunden (Sitzung fortgesetzt)");
                    } else {
                        Thread t = new Thread(() -> subscribeAll(sessionId, broadcasterId), "TwitchChaos-Subscribe");
                        t.setDaemon(true);
                        t.start();
                    }
                }
                case "session_keepalive" -> {
                    // nur Lebenszeichen
                }
                case "session_reconnect" -> {
                    String newUrl = TwitchAuth.str(payload.getAsJsonObject("session"), "reconnect_url");
                    if (!newUrl.isEmpty() && newUrl.startsWith("wss://")) {
                        Conn next = new Conn(newUrl, true, this, broadcasterId);
                        current = next;
                        next.open();
                    }
                }
                case "revocation" -> {
                    String subType = TwitchAuth.str(payload.getAsJsonObject("subscription"), "type");
                    LOG.warn("Twitch hat das Abo '{}' widerrufen - Verbindung wird erneuert.", subType);
                    close();
                    onClosed(this, "Abo widerrufen");
                }
                case "notification" -> notification(payload);
                default -> LOG.debug("Unbekannter Nachrichtentyp: {}", type);
            }
        }
    }

    private boolean isDuplicate(String messageId) {
        synchronized (seenMessages) {
            if (!seenMessages.add(messageId)) return true;
            if (seenMessages.size() > 500) {
                var it = seenMessages.iterator();
                it.next();
                it.remove();
            }
            return false;
        }
    }

    // ---------------------------------------------------------------- Abos

    private record Sub(String label, String type, String version, JsonObject condition) {
    }

    private void subscribeAll(String sessionId, String broadcasterId) {
        String me = auth.userId();
        List<Sub> subs = new ArrayList<>();
        subs.add(new Sub("Follows", "channel.follow", "2", cond("broadcaster_user_id", broadcasterId, "moderator_user_id", me)));
        subs.add(new Sub("Subs", "channel.subscribe", "1", cond("broadcaster_user_id", broadcasterId)));
        subs.add(new Sub("Resubs", "channel.subscription.message", "1", cond("broadcaster_user_id", broadcasterId)));
        subs.add(new Sub("Gift-Subs", "channel.subscription.gift", "1", cond("broadcaster_user_id", broadcasterId)));
        subs.add(new Sub("Bits", "channel.cheer", "1", cond("broadcaster_user_id", broadcasterId)));
        subs.add(new Sub("Raids", "channel.raid", "1", cond("to_broadcaster_user_id", broadcasterId)));
        subs.add(new Sub("Kanalpunkte", "channel.channel_points_custom_reward_redemption.add", "1",
                cond("broadcaster_user_id", broadcasterId)));

        int ok = 0;
        List<String> failed = new ArrayList<>();
        for (Sub s : subs) {
            try {
                JsonObject transport = new JsonObject();
                transport.addProperty("method", "websocket");
                transport.addProperty("session_id", sessionId);
                JsonObject body = new JsonObject();
                body.addProperty("type", s.type());
                body.addProperty("version", s.version());
                body.add("condition", s.condition());
                body.add("transport", transport);
                TwitchAuth.HttpResult r = auth.helix("POST", SUBS_URL, body.toString());
                if (r.status() == 202 || r.status() == 200) {
                    ok++;
                } else {
                    failed.add(s.label() + " (HTTP " + r.status() + ")");
                    LOG.warn("Abo {} fehlgeschlagen: HTTP {} {}", s.type(), r.status(), TwitchAuth.str(r.json(), "message"));
                }
            } catch (Exception e) {
                failed.add(s.label());
                LOG.warn("Abo {} fehlgeschlagen: {}", s.type(), e.toString());
            }
        }
        String channel = cfg.channel == null || cfg.channel.isBlank() ? auth.login() : cfg.channel;
        String text = "Verbunden mit '" + channel + "' (" + ok + "/" + subs.size() + " Ereignisse aktiv)";
        if (!failed.isEmpty()) text += " - nicht verfuegbar: " + String.join(", ", failed);
        setState(State.CONNECTED, text);
    }

    private static JsonObject cond(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        return o;
    }

    // ---------------------------------------------------------------- Ereignisse

    private void notification(JsonObject payload) {
        String type = TwitchAuth.str(payload.getAsJsonObject("subscription"), "type");
        JsonObject e = payload.getAsJsonObject("event");
        if (e == null) return;

        TwitchEvent out = switch (type) {
            case "channel.follow" ->
                    new TwitchEvent(TwitchEvent.Type.FOLLOW, TwitchEvent.cleanUser(TwitchAuth.str(e, "user_name")), 0, 0, 0);
            case "channel.subscribe" -> {
                // Geschenkte Subs melden Twitch zusaetzlich als "gift" - hier ignorieren, sonst doppelt.
                if (e.has("is_gift") && e.get("is_gift").getAsBoolean()) yield null;
                yield new TwitchEvent(TwitchEvent.Type.SUB, TwitchEvent.cleanUser(TwitchAuth.str(e, "user_name")),
                        0, tier(e), 0);
            }
            case "channel.subscription.message" ->
                    new TwitchEvent(TwitchEvent.Type.RESUB, TwitchEvent.cleanUser(TwitchAuth.str(e, "user_name")),
                            0, tier(e), intOf(e, "cumulative_months"));
            case "channel.subscription.gift" ->
                    new TwitchEvent(TwitchEvent.Type.GIFT, TwitchEvent.cleanUser(TwitchAuth.str(e, "user_name")),
                            Math.max(1, intOf(e, "total")), tier(e), 0);
            case "channel.cheer" ->
                    new TwitchEvent(TwitchEvent.Type.BITS, TwitchEvent.cleanUser(TwitchAuth.str(e, "user_name")),
                            intOf(e, "bits"), 0, 0);
            case "channel.raid" ->
                    new TwitchEvent(TwitchEvent.Type.RAID,
                            TwitchEvent.cleanUser(TwitchAuth.str(e, "from_broadcaster_user_name")),
                            intOf(e, "viewers"), 0, 0);
            case "channel.channel_points_custom_reward_redemption.add" ->
                    new TwitchEvent(TwitchEvent.Type.POINTS, TwitchEvent.cleanUser(TwitchAuth.str(e, "user_name")), 0, 0, 0);
            default -> null;
        };
        if (out != null) {
            try {
                sink.accept(out);
            } catch (Exception ex) {
                LOG.warn("Ereignis konnte nicht weitergegeben werden: {}", ex.toString());
            }
        }
    }

    private static int tier(JsonObject e) {
        String t = TwitchAuth.str(e, "tier");
        return switch (t) {
            case "3000" -> 3;
            case "2000" -> 2;
            default -> 1;
        };
    }

    private static int intOf(JsonObject o, String key) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) return 0;
        try {
            return el.getAsInt();
        } catch (Exception ex) {
            return 0;
        }
    }
}
