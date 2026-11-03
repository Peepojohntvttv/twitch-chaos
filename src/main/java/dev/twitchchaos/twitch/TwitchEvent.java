package dev.twitchchaos.twitch;

/**
 * Ein bereits ausgewertetes Twitch-Ereignis. Enthaelt nur Daten - Twitch-Texte werden
 * niemals als Minecraft-Befehl ausgefuehrt.
 *
 * @param type   Art des Ereignisses
 * @param user   Anzeigename (bereinigt, max. 25 Zeichen); "Anonym" falls nicht vorhanden
 * @param amount Bits, Anzahl verschenkter Subs oder Zuschauerzahl (Raid), sonst 0
 * @param tier   Sub-Stufe 1-3 (0 falls nicht zutreffend)
 * @param months Gesamtmonate bei Resub
 */
public record TwitchEvent(Type type, String user, int amount, int tier, int months) {

    public enum Type {
        FOLLOW, SUB, RESUB, GIFT, BITS, RAID, POINTS
    }

    public static String cleanUser(String raw) {
        if (raw == null || raw.isBlank()) return "Anonym";
        String s = raw.replaceAll("[^\\p{L}\\p{N}_]", "");
        if (s.isEmpty()) return "Anonym";
        return s.length() > 25 ? s.substring(0, 25) : s;
    }
}
