package dev.twitchchaos.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/** Alle Chaos-Events. Jedes Event fuehrt echte Aktionen im Spiel aus. */
public final class ChaosEvents {

    @FunctionalInterface
    public interface Action {
        void run(EventContext c);
    }

    public record Def(String id, String name, Rarity rarity, Action action) {
    }

    private static final Map<String, Def> BY_ID = new LinkedHashMap<>();

    private static final String[] MOB_MIX = {"zombie", "skeleton", "spider", "creeper", "husk", "stray", "zombie", "skeleton"};
    private static final String[] EFFECTS = {"speed", "slowness", "jump_boost", "nausea", "levitation", "glowing",
            "strength", "night_vision", "weakness", "haste", "mining_fatigue"};

    private ChaosEvents() {
    }

    public static Collection<Def> all() {
        return BY_ID.values();
    }

    public static Def get(String id) {
        return BY_ID.get(id.toLowerCase(Locale.ROOT));
    }

    public static List<Def> of(Rarity r) {
        List<Def> out = new ArrayList<>();
        for (Def d : BY_ID.values()) if (d.rarity() == r) out.add(d);
        return out;
    }

    /** Zufaelliges Event aus einer Seltenheitsstufe. */
    public static Def pick(Random rnd, Rarity r) {
        List<Def> list = of(r);
        return list.get(rnd.nextInt(list.size()));
    }

    /** Gewichtete Auswahl ueber die drei Stufen. */
    public static Def pickWeighted(Random rnd, int wNormal, int wRare, int wSpecial) {
        int total = Math.max(1, wNormal + wRare + wSpecial);
        int roll = rnd.nextInt(total);
        if (roll < wNormal) return pick(rnd, Rarity.NORMAL);
        if (roll < wNormal + wRare) return pick(rnd, Rarity.RARE);
        return pick(rnd, Rarity.SPECIAL);
    }

    private static void reg(String id, String name, Rarity rarity, Action action) {
        BY_ID.put(id, new Def(id, name, rarity, action));
    }

    private static void effect(EventContext c, String effect, int seconds, int amplifier) {
        c.run("effect give " + c.target + " minecraft:" + effect + " " + seconds + " " + amplifier + " true");
    }

    private static void give(EventContext c, String item, int count) {
        c.run("give " + c.target + " minecraft:" + item + " " + count);
    }

    static {
        // ======================================================== NORMAL
        reg("zombie", "Ein Zombie erscheint", Rarity.NORMAL, c -> c.spawn("zombie", ""));

        reg("spiders", "Spinnenplage", Rarity.NORMAL, c -> c.spawnMany("spider", c.mobs(4)));

        reg("skeletons", "Skelett-Patrouille", Rarity.NORMAL, c -> c.spawnMany("skeleton", c.mobs(3)));

        reg("creepers", "Creeper-Besuch", Rarity.NORMAL, c -> c.spawnMany("creeper", c.mobs(2)));

        reg("chickens", "Huehnerregen", Rarity.NORMAL, c -> {
            for (int i = 0; i < 12; i++) c.run("summon minecraft:chicken " + c.ringHigh(1, 5, 8));
        });

        reg("effect", "Zufaelliger Effekt", Rarity.NORMAL, c -> {
            String e = EFFECTS[c.rnd().nextInt(EFFECTS.length)];
            effect(c, e, 20 + c.rnd().nextInt(20), c.rnd().nextInt(2));
        });

        reg("lightning", "Blitzeinschlag in der Naehe", Rarity.NORMAL,
                c -> c.run("summon minecraft:lightning_bolt " + c.ring(3, 6)));

        reg("teleport", "Zufaelliger Teleport", Rarity.NORMAL,
                c -> c.run("spreadplayers ~ ~ 10 40 false " + c.target));

        reg("cage", "Eisenkaefig (10 Sekunden)", Rarity.NORMAL, ChaosEvents::cage);

        reg("moon", "Mondgravitation (30 Sekunden)", Rarity.NORMAL, c -> {
            effect(c, "slow_falling", 30, 0);
            effect(c, "jump_boost", 30, 4);
        });

        reg("darkness", "Dunkelheit", Rarity.NORMAL, c -> effect(c, "darkness", 15, 0));

        reg("hunger", "Heisshunger", Rarity.NORMAL, c -> effect(c, "hunger", 20, 4));

        // ======================================================== SELTEN
        reg("mimic", "Ein Mimic erscheint!", Rarity.RARE, c -> c.spawnNamed("zombie", "Mimic",
                "{" + EventContext.named("Mimic") + ",IsBaby:1b,Health:40.0f,"
                        + "attributes:[{id:\"minecraft:max_health\",base:40.0},{id:\"minecraft:movement_speed\",base:0.34}],"
                        + "equipment:{head:{id:\"minecraft:chest\",count:1}}}"));

        reg("wave", "Monsterwelle", Rarity.RARE, c -> wave(c, 12));

        reg("miniboss", "Ein Mini-Boss erscheint!", Rarity.RARE, c -> c.spawnNamed("zombie", "Mini-Boss",
                "{" + EventContext.named("Mini-Boss") + ",Health:100.0f,"
                        + "attributes:[{id:\"minecraft:max_health\",base:100.0},{id:\"minecraft:attack_damage\",base:6.0}],"
                        + "equipment:{mainhand:{id:\"minecraft:iron_sword\",count:1},head:{id:\"minecraft:iron_helmet\",count:1},"
                        + "chest:{id:\"minecraft:iron_chestplate\",count:1}}}"));

        reg("reward", "Zufaellige Belohnung", Rarity.RARE, c -> {
            String[][] loot = {
                    {"diamond", "3"}, {"emerald", "12"}, {"golden_apple", "2"}, {"iron_ingot", "16"},
                    {"cooked_beef", "16"}, {"ender_pearl", "4"}, {"experience_bottle", "8"}, {"arrow", "32"}};
            for (int i = 0; i < 2; i++) {
                String[] l = loot[c.rnd().nextInt(loot.length)];
                give(c, l[0], Integer.parseInt(l[1]));
            }
        });

        reg("danger", "Gefaehrliches Chaos!", Rarity.RARE, c -> {
            effect(c, "darkness", 20, 0);
            effect(c, "slowness", 10, 1);
            c.spawnMany("creeper", c.mobs(3));
            c.later(40, () -> c.run("summon minecraft:lightning_bolt " + c.ring(4, 7)));
        });

        reg("piglins", "Piglin-Ueberfall", Rarity.RARE, c -> c.spawnMany("piglin_brute", c.mobs(4)));

        // ======================================================== BESONDERS
        reg("boss", "RIESIGER BOSSKAMPF: Chaos-Titan!", Rarity.SPECIAL, c -> {
            effect(c, "absorption", 120, 4);
            c.spawnNamed("wither_skeleton", "Chaos-Titan",
                    "{" + EventContext.named("Chaos-Titan") + ",Health:250.0f,"
                            + "attributes:[{id:\"minecraft:max_health\",base:250.0},{id:\"minecraft:attack_damage\",base:9.0},"
                            + "{id:\"minecraft:scale\",base:1.6}],"
                            + "equipment:{mainhand:{id:\"minecraft:netherite_sword\",count:1},"
                            + "head:{id:\"minecraft:netherite_helmet\",count:1}}}");
        });

        reg("invasion", "MONSTERINVASION!", Rarity.SPECIAL, c -> c.spawnOverTime(MOB_MIX, c.mobs(30), 8));

        reg("jackpot", "JACKPOT!", Rarity.SPECIAL, c -> {
            give(c, "diamond", 8);
            give(c, "emerald", 16);
            give(c, "golden_apple", 3);
            give(c, "netherite_ingot", 1);
            c.run("experience add " + c.target + " 1000 points");
        });

        reg("blessing", "Segen der Chaos-Goetter", Rarity.SPECIAL, c -> {
            effect(c, "regeneration", 60, 1);
            effect(c, "strength", 60, 1);
            effect(c, "speed", 60, 1);
            effect(c, "saturation", 10, 1);
            give(c, "totem_of_undying", 1);
        });
    }

    /** Staerke einer Welle: Basisanzahl, bei Raids steigt sie mit der Zuschauerzahl. */
    public static void wave(EventContext c, int base) {
        int count = c.mobs(base + c.power / 3);
        c.spawnOverTime(MOB_MIX, count, 5);
    }

    private static void cage(EventContext c) {
        int x = c.player.blockPosition().getX();
        int y = c.player.blockPosition().getY();
        int z = c.player.blockPosition().getZ();
        String box = String.format(Locale.ROOT, "%d %d %d %d %d %d", x - 1, y - 1, z - 1, x + 1, y + 2, z + 1);
        // Nur Luft wird ersetzt - bestehende Bloecke bleiben unangetastet.
        c.runFixed("fill " + box + " minecraft:iron_bars replace minecraft:air");
        // Platz fuer den Spieler wieder freiraeumen.
        c.runFixed(String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:air replace minecraft:iron_bars",
                x, y, z, x, y + 1, z));
        c.later(200, () -> c.runFixed("fill " + box + " minecraft:air replace minecraft:iron_bars"));
    }
}
