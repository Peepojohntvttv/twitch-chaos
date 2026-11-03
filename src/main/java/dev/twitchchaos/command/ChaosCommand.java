package dev.twitchchaos.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.twitchchaos.TwitchChaosMod;
import dev.twitchchaos.core.ChaosEvents;
import dev.twitchchaos.net.ServerNet;
import dev.twitchchaos.twitch.TwitchEvent;
import dev.twitchchaos.util.Perm;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/** /twitchchaos ... - "help" darf jeder, alles andere nur berechtigte Personen. */
public final class ChaosCommand {
    private ChaosCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> build(dispatcher));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> admin(String name) {
        return Commands.literal(name).requires(Perm::canAdmin);
    }

    private static void build(CommandDispatcher<CommandSourceStack> d) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("twitchchaos");

        root.then(Commands.literal("help").executes(ctx -> {
            for (String line : List.of(
                    "=== Twitch Chaos ===",
                    "/twitchchaos gui - Einstellungsmenue oeffnen",
                    "/twitchchaos start | stop - Chaos-Modus ohne Twitch (Zufallsevents im Intervall)",
                    "/twitchchaos event random | <id> - Event sofort ausloesen",
                    "/twitchchaos test follow|sub|resub|gift|bits|raid|points - Twitch-Ereignis simulieren",
                    "/twitchchaos connect | disconnect - Twitch-Verbindung",
                    "/twitchchaos status | reload | events")) {
                String l = line;
                ctx.getSource().sendSuccess(() -> Component.literal(l), false);
            }
            return 1;
        }));

        root.then(admin("gui").executes(ctx -> {
            ServerPlayer p = ctx.getSource().getPlayer();
            if (p == null) {
                ctx.getSource().sendFailure(Component.literal("Nur ein Spieler kann das Menue oeffnen."));
                return 0;
            }
            ServerNet.sendState(p, true);
            return 1;
        }));

        root.then(admin("start").executes(ctx -> {
            if (TwitchChaosMod.manager == null) return 0;
            TwitchChaosMod.manager.setStandalone(true);
            ctx.getSource().sendSuccess(() -> Component.literal("Chaos-Modus gestartet (alle "
                    + TwitchChaosMod.config.standaloneIntervalSeconds + " s ein Zufallsevent)."), true);
            return 1;
        }));

        root.then(admin("stop").executes(ctx -> {
            if (TwitchChaosMod.manager == null) return 0;
            TwitchChaosMod.manager.setStandalone(false);
            ctx.getSource().sendSuccess(() -> Component.literal("Chaos-Modus gestoppt."), true);
            return 1;
        }));

        root.then(admin("reload").executes(ctx -> {
            TwitchChaosMod.config.reload();
            ctx.getSource().sendSuccess(() -> Component.literal("Konfiguration neu geladen."), false);
            return 1;
        }));

        root.then(admin("connect").executes(ctx -> {
            if (TwitchChaosMod.twitch == null) return 0;
            TwitchChaosMod.config.sanitize();
            TwitchChaosMod.twitch.connect();
            ctx.getSource().sendSuccess(() -> Component.literal("Verbinde mit Twitch ... Status: /twitchchaos status"), false);
            return 1;
        }));

        root.then(admin("disconnect").executes(ctx -> {
            if (TwitchChaosMod.twitch != null) TwitchChaosMod.twitch.disconnect();
            ctx.getSource().sendSuccess(() -> Component.literal("Twitch-Verbindung getrennt."), false);
            return 1;
        }));

        root.then(admin("status").executes(ctx -> {
            String s = TwitchChaosMod.twitch == null ? "Nicht verbunden" : TwitchChaosMod.twitch.status();
            String code = TwitchChaosMod.twitch == null ? "" : TwitchChaosMod.twitch.userCode();
            String url = TwitchChaosMod.twitch == null ? "" : TwitchChaosMod.twitch.deviceUrl();
            ctx.getSource().sendSuccess(() -> Component.literal("Twitch: " + s
                    + (code.isEmpty() ? "" : " [Code " + code + " auf " + url + "]")
                    + " | Chaos-Modus: " + (TwitchChaosMod.manager != null && TwitchChaosMod.manager.standalone() ? "an" : "aus")
                    + " | Warteschlange: " + (TwitchChaosMod.manager == null ? 0 : TwitchChaosMod.manager.queueSize())), false);
            return 1;
        }));

        root.then(admin("events").executes(ctx -> {
            StringBuilder sb = new StringBuilder("Events: ");
            for (ChaosEvents.Def def : ChaosEvents.all()) sb.append(def.id()).append(" ");
            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString().trim()), false);
            return 1;
        }));

        // /twitchchaos event <random|id> - je ein Unterbefehl pro Event (Tab-Vervollstaendigung inklusive)
        LiteralArgumentBuilder<CommandSourceStack> event = admin("event");
        event.then(Commands.literal("random").executes(ctx -> runEvent(ctx.getSource(), "random")));
        for (ChaosEvents.Def def : ChaosEvents.all()) {
            event.then(Commands.literal(def.id()).executes(ctx -> runEvent(ctx.getSource(), def.id())));
        }
        root.then(event);

        // /twitchchaos test <typ>
        LiteralArgumentBuilder<CommandSourceStack> test = admin("test");
        for (String type : List.of("follow", "sub", "resub", "gift", "bits", "raid", "points")) {
            test.then(Commands.literal(type).executes(ctx -> {
                TwitchEvent ev = ServerNet.testEvent(type);
                if (TwitchChaosMod.manager == null || ev == null || !TwitchChaosMod.manager.test(ev)) {
                    ctx.getSource().sendFailure(Component.literal("Kein Spieler online."));
                    return 0;
                }
                return 1;
            }));
        }
        root.then(test);

        d.register(root);
    }

    private static int runEvent(CommandSourceStack src, String id) {
        if (TwitchChaosMod.manager == null) return 0;
        ServerPlayer p = src.getPlayer();
        String who = p != null ? p.getScoreboardName() : "Konsole";
        if (!TwitchChaosMod.manager.runById(id, p, who)) {
            src.sendFailure(Component.literal("Event nicht moeglich (unbekannt oder kein Spieler online)."));
            return 0;
        }
        return 1;
    }
}
