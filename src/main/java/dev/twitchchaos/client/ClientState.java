package dev.twitchchaos.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Letzter vom Server empfangener Zustand (nur Anzeige - die Wahrheit liegt immer auf dem Server). */
public final class ClientState {
    private static JsonObject root = new JsonObject();

    private ClientState() {
    }

    public static void update(String json) {
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception ignored) {
            // fehlerhafte Daten ignorieren
        }
    }

    private static JsonObject cfg() {
        return root.has("config") ? root.getAsJsonObject("config") : new JsonObject();
    }

    private static JsonObject tw() {
        return root.has("twitch") ? root.getAsJsonObject("twitch") : new JsonObject();
    }

    public static boolean flag(String key) {
        JsonElement e = cfg().get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    public static int num(String key) {
        JsonElement e = cfg().get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsInt() : 0;
    }

    public static String text(String key) {
        JsonElement e = cfg().get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    public static String twitch(String key) {
        JsonElement e = tw().get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    public static boolean standalone() {
        return root.has("standalone") && root.get("standalone").getAsBoolean();
    }

    public static boolean admin() {
        return root.has("admin") && root.get("admin").getAsBoolean();
    }

    public static int queue() {
        return root.has("queue") ? root.get("queue").getAsInt() : 0;
    }

    public static JsonArray events() {
        return root.has("events") ? root.getAsJsonArray("events") : new JsonArray();
    }
}
