package dev.twitchchaos.twitch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Twitch-Anmeldung per "Device Code Grant Flow". Dafuer wird KEIN Client-Secret benoetigt:
 * Der Streamer registriert eine eigene Twitch-Anwendung (Typ "Oeffentlich"), traegt deren Client-ID
 * im Menue ein und bestaetigt den angezeigten Code im Browser.
 * Tokens werden nur lokal in config/twitchchaos_auth.json gespeichert (nie im Quellcode, nie im Log).
 */
public final class TwitchAuth {
    private static final Logger LOG = LoggerFactory.getLogger("TwitchChaos/Auth");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String SCOPES =
            "moderator:read:followers channel:read:subscriptions bits:read channel:read:redemptions";

    private final Path file;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private String clientId = "";
    private String accessToken = "";
    private String refreshToken = "";
    private String userId = "";
    private String login = "";

    public record DeviceCode(String deviceCode, String userCode, String verificationUri, int expiresIn, int interval) {
    }

    public record HttpResult(int status, JsonObject json) {
    }

    public TwitchAuth(Path file) {
        this.file = file;
        load();
    }

    // ---------------------------------------------------------------- Zugriff

    public synchronized String clientId() {
        return clientId;
    }

    public synchronized String accessToken() {
        return accessToken;
    }

    public synchronized String userId() {
        return userId;
    }

    public synchronized String login() {
        return login;
    }

    public synchronized boolean hasToken() {
        return !accessToken.isEmpty();
    }

    // ---------------------------------------------------------------- Device Code Flow

    public DeviceCode requestDeviceCode(String clientId) throws IOException, InterruptedException {
        HttpResult r = postForm("https://id.twitch.tv/oauth2/device",
                Map.of("client_id", clientId, "scopes", SCOPES));
        if (r.status() != 200) {
            throw new IOException("Twitch lehnte die Anmeldung ab (HTTP " + r.status() + "): "
                    + str(r.json(), "message") + " - Pruefe die Client-ID.");
        }
        return new DeviceCode(
                str(r.json(), "device_code"),
                str(r.json(), "user_code"),
                str(r.json(), "verification_uri"),
                r.json().has("expires_in") ? r.json().get("expires_in").getAsInt() : 600,
                r.json().has("interval") ? r.json().get("interval").getAsInt() : 5);
    }

    /** Wartet, bis der Nutzer den Code im Browser bestaetigt hat. Gibt true bei Erfolg zurueck. */
    public boolean pollForToken(String clientId, DeviceCode dc, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + dc.expiresIn() * 1000L;
        int interval = Math.max(2, dc.interval());
        while (System.currentTimeMillis() < deadline && !cancelled.getAsBoolean()) {
            Thread.sleep(interval * 1000L);
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", clientId);
            form.put("scopes", SCOPES);
            form.put("device_code", dc.deviceCode());
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            HttpResult r = postForm("https://id.twitch.tv/oauth2/token", form);
            if (r.status() == 200) {
                storeTokens(clientId, r.json());
                return true;
            }
            String msg = str(r.json(), "message");
            if (msg.contains("authorization_pending")) continue;
            if (msg.contains("slow_down")) {
                interval += 5;
                continue;
            }
            throw new IOException("Anmeldung fehlgeschlagen: " + msg);
        }
        return false;
    }

    // ---------------------------------------------------------------- Token pflegen

    /** Erneuert das Access-Token mit dem Refresh-Token. */
    public boolean refresh() {
        String rt;
        String cid;
        synchronized (this) {
            rt = refreshToken;
            cid = clientId;
        }
        if (rt.isEmpty() || cid.isEmpty()) return false;
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("client_id", cid);
            form.put("grant_type", "refresh_token");
            form.put("refresh_token", rt);
            HttpResult r = postForm("https://id.twitch.tv/oauth2/token", form);
            if (r.status() == 200) {
                storeTokens(cid, r.json());
                return true;
            }
            LOG.warn("Token-Erneuerung fehlgeschlagen (HTTP {})", r.status());
        } catch (Exception e) {
            LOG.warn("Token-Erneuerung fehlgeschlagen: {}", e.toString());
        }
        return false;
    }

    /** Prueft das Token bei Twitch und liest Benutzer-ID und Login aus. */
    public boolean validate() {
        String at;
        synchronized (this) {
            at = accessToken;
        }
        if (at.isEmpty()) return false;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://id.twitch.tv/oauth2/validate"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "OAuth " + at)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return false;
            JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
            synchronized (this) {
                userId = str(o, "user_id");
                login = str(o, "login");
            }
            save();
            return true;
        } catch (Exception e) {
            LOG.warn("Token-Pruefung fehlgeschlagen: {}", e.toString());
            return false;
        }
    }

    /** Meldet das Token bei Twitch ab und loescht die lokale Datei. */
    public void logout() {
        String at;
        String cid;
        synchronized (this) {
            at = accessToken;
            cid = clientId;
            accessToken = "";
            refreshToken = "";
            userId = "";
            login = "";
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Auth-Datei konnte nicht geloescht werden: {}", e.toString());
        }
        if (!at.isEmpty() && !cid.isEmpty()) {
            try {
                postForm("https://id.twitch.tv/oauth2/revoke", Map.of("client_id", cid, "token", at));
            } catch (Exception ignored) {
                // Widerrufen ist "best effort".
            }
        }
    }

    // ---------------------------------------------------------------- Helix-Hilfen

    /** Sendet eine Helix-Anfrage mit Bearer-Token. Bei 401 wird einmal das Token erneuert. */
    public HttpResult helix(String method, String url, String jsonBody) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Client-Id", clientId());
            if (jsonBody != null) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401 && attempt == 0 && refresh()) continue;
            return new HttpResult(resp.statusCode(), parse(resp.body()));
        }
        return new HttpResult(401, new JsonObject());
    }

    // ---------------------------------------------------------------- intern

    private synchronized void storeTokens(String cid, JsonObject o) {
        clientId = cid;
        accessToken = str(o, "access_token");
        refreshToken = str(o, "refresh_token");
        save();
    }

    private HttpResult postForm(String url, Map<String, String> form) throws IOException, InterruptedException {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return new HttpResult(resp.statusCode(), parse(resp.body()));
    }

    private static JsonObject parse(String body) {
        try {
            if (body != null && !body.isBlank()) return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception ignored) {
            // kein JSON -> leeres Objekt
        }
        return new JsonObject();
    }

    static String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private synchronized void load() {
        try {
            if (!Files.exists(file)) return;
            JsonObject o = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            clientId = str(o, "clientId");
            accessToken = str(o, "accessToken");
            refreshToken = str(o, "refreshToken");
            userId = str(o, "userId");
            login = str(o, "login");
        } catch (Exception e) {
            LOG.warn("Auth-Datei konnte nicht gelesen werden: {}", e.toString());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("clientId", clientId);
            o.addProperty("accessToken", accessToken);
            o.addProperty("refreshToken", refreshToken);
            o.addProperty("userId", userId);
            o.addProperty("login", login);
            Files.writeString(file, GSON.toJson(o), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows: keine POSIX-Rechte
            }
        } catch (IOException e) {
            LOG.error("Auth-Datei konnte nicht gespeichert werden: {}", e.toString());
        }
    }
}
