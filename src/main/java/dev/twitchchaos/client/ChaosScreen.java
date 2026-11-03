
package dev.twitchchaos.client;

import com.google.gson.JsonElement;
import dev.twitchchaos.net.ActionPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ChaosScreen extends Screen {

    private static final int PW_MAX = 400;
    private static final int PH_MAX = 252;

    private static boolean english = false;

    private static final int COL_BORDER = 0xFFA855F7;
    private static final int COL_PANEL_TOP = 0xF01B0C33;
    private static final int COL_PANEL_BOTTOM = 0xF0120822;
    private static final int COL_HEADER_TOP = 0xFF5B21B6;
    private static final int COL_HEADER_BOTTOM = 0xFF3B0F7A;
    private static final int COL_CARD = 0x7A2A1250;
    private static final int COL_LINE = 0x55A855F7;
    private static final int COL_INPUT_BG = 0xFF0D0518;
    private static final int COL_INPUT_BORDER = 0xFF6B3FA0;
    private static final int COL_ACCENT = 0xFFA855F7;
    private static final int COL_TEXT = 0xFFE9D5FF;
    private static final int COL_MUTED = 0xFF9A8DB5;
    private static final int COL_OK = 0xFF4ADE80;
    private static final int COL_WARN = 0xFFFACC15;
    private static final int COL_ERR = 0xFFF87171;

    private static final int EVENT_COLS = 4;
    private static final int EVENT_ROWS = 3;

    private record Label(String text, int x, int y, int color) {}
    private record Box(int x1, int y1, int x2, int y2, int color) {}

    private final List<Label> labels = new ArrayList<>();
    private final List<Box> boxes = new ArrayList<>();

    private int tab = 0;
    private int eventPage = 0;

    private int left;
    private int top;
    private int pw = PW_MAX;
    private int ph = PH_MAX;

    private String channelText;
    private String clientIdText;
    private String targetText;

    private boolean fieldsInitialised;

    public ChaosScreen() {
        super(Component.literal("Twitch Chaos"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public void onStateChanged() {
        rebuild();
    }

    @Override
    protected void init() {
        if (!fieldsInitialised) {
            channelText = ClientState.text("channel");
            clientIdText = ClientState.text("clientId");
            targetText = ClientState.text("targetPlayer");
            fieldsInitialised = true;
        }

        rebuild();
        send("refresh", "");
    }

    private void rebuild() {
        clearWidgets();
        labels.clear();
        boxes.clear();

        pw = Math.min(PW_MAX, Math.max(320, this.width - 8));
        ph = Math.min(PH_MAX, Math.max(220, this.height - 4));

        left = (this.width - pw) / 2;
        top = Math.max(2, (this.height - ph) / 2);

        String[] names = english
                ? new String[]{"Connection", "Events", "Values", "Chaos"}
                : new String[]{"Verbindung", "Ereignisse", "Werte", "Chaos"};

        int tw = (pw - 32) / 4;

        for (int i = 0; i < 4; i++) {
            final int idx = i;

            int bx = left + 10 + i * (tw + 4);

            if (i == tab) {
                boxes.add(new Box(bx, top + 51, bx + tw, top + 53, COL_ACCENT));
            }

            addButton(
                    bx, top + 29, tw, 20,
                    names[i],
                    () -> {
                        tab = idx;
                        rebuild();
                    }
            );
        }

        Button lang = addButton(
                left + pw - 44,
                top + 3,
                34,
                18,
                english ? "DE" : "EN",
                () -> {
                    english = !english;
                    rebuild();
                }
        );

        lang.setTooltip(Tooltip.create(Component.literal(
                english ? "Sprache: Deutsch" : "Language: English"
        )));

        int y0 = top + 59;

        switch (tab) {
            case 0 -> buildConnection(y0);
            case 1 -> buildEvents(y0);
            case 2 -> buildValues(y0);
            default -> buildChaos(y0);
        }

        boxes.add(new Box(
                left + 10,
                top + ph - 31,
                left + pw - 10,
                top + ph - 30,
                COL_LINE
        ));

        addButton(
                left + pw - 90,
                top + ph - 26,
                80,
                20,
                english ? "Close" : "Schliessen",
                this::onClose
        );

        if (!ClientState.admin()) {
            labels.add(new Label(
                    tr("Read-only: no permission", "Nur Ansicht: keine Berechtigung"),
                    left + 12,
                    top + ph - 20,
                    COL_WARN
            ));
        }
    }

    // ------------------------------------------------------------
    // Button-Helfer: verwendet echte Minecraft-Buttons
    // ------------------------------------------------------------

    private Button addButton(
            int x,
            int y,
            int w,
            int h,
            String text,
            Runnable action
    ) {
        Button button = Button.builder(
                Component.literal(text),
                b -> action.run()
        ).bounds(x, y, w, h).build();

        addRenderableWidget(button);
        return button;
    }

    private Button toggle(
            String name,
            String key,
            boolean on,
            int x,
            int y,
            int w
    ) {
        String state = on
                ? (english ? "ON" : "AN")
                : (english ? "OFF" : "AUS");

        return addButton(
                x, y, w, 20,
                name + ": " + state,
                () -> send("toggle", key)
        );
    }

    private static String tr(String en, String de) {
        return english ? en : de;
    }

    private void send(String action, String arg) {
        ClientPlayNetworking.send(new ActionPayload(action, arg));
    }

    // ------------------------------------------------------------
    // Seite 1: Verbindung
    // ------------------------------------------------------------

    private void buildConnection(int y) {
        int fx = left + 10;
        int fw = pw - 20;
        int cw = (pw - 32) / 2;
        int x2 = left + 16 + cw;

        boxes.add(new Box(
                fx - 4, y - 4,
                fx + cw + 4, y + 92,
                COL_CARD
        ));

        labels.add(new Label(
                tr("Twitch channel (empty = own)", "Twitch-Kanal (leer = eigener)"),
                fx, y, COL_MUTED
        ));

        input(
                fx, y + 10, cw,
                english ? "Channel" : "Kanal",
                25,
                channelText,
                s -> channelText = s
        );

        labels.add(new Label(
                tr("Client ID (see README)", "Client-ID (siehe README)"),
                fx, y + 30, COL_MUTED
        ));

        input(
                fx, y + 40, cw,
                "Client ID",
                64,
                clientIdText,
                s -> clientIdText = s
        );

        labels.add(new Label(
                tr("Target player (empty = random)", "Ziel-Spieler (leer = zufaellig)"),
                fx, y + 60, COL_MUTED
        ));

        input(
                fx, y + 70, cw,
                english ? "Player" : "Spieler",
                16,
                targetText,
                s -> targetText = s
        );

        boxes.add(new Box(
                x2 - 4, y - 4,
                x2 + cw + 4, y + 92,
                COL_CARD
        ));

        int bw = (cw - 4) / 2;

        addButton(
                x2, y, bw, 20,
                english ? "Save" : "Speichern",
                this::saveFields
        );

        addButton(
                x2 + bw + 4, y, bw, 20,
                english ? "Connect" : "Verbinden",
                () -> {
                    saveFields();
                    send("connect", "");
                }
        );

        addButton(
                x2, y + 24, bw, 20,
                english ? "Disconnect" : "Trennen",
                () -> send("disconnect", "")
        );

        Button logout = addButton(
                x2 + bw + 4, y + 24, bw, 20,
                english ? "Log out" : "Abmelden",
                () -> send("logout", "")
        );

        logout.setTooltip(Tooltip.create(Component.literal(
                tr("Disconnect and remove login", "Verbindung trennen und Anmeldung entfernen")
        )));

        toggle(
                tr("Auto-connect", "Auto-Verbinden"),
                "autoconnect",
                ClientState.flag("autoConnect"),
                x2, y + 52, cw
        );

        labels.add(new Label(
                tr("Connects on game start", "Verbindet beim Spielstart"),
                x2 + 1, y + 76, COL_MUTED
        ));

        String code = ClientState.twitch("userCode");
        boolean hasCode = !code.isEmpty();

        int cy = y + 102;
        int ch = hasCode ? 46 : 14;

        boxes.add(new Box(
                fx - 4, cy - 4,
                fx + fw + 4, cy + ch,
                COL_CARD
        ));

        boxes.add(new Box(
                fx - 4, cy - 4,
                fx - 2, cy + ch,
                statusColor()
        ));

        labels.add(new Label(
                "Status: " + ClientState.twitch("status"),
                fx + 4, cy, statusColor()
        ));

        if (hasCode) {
            labels.add(new Label(
                    (english ? "Open " : "Oeffne ") + ClientState.twitch("deviceUrl"),
                    fx + 4, cy + 12, COL_TEXT
            ));

            labels.add(new Label(
                    (english ? "Enter code: " : "Code eingeben: ") + code,
                    fx + 4, cy + 24, COL_ACCENT
            ));

            Button copy = addButton(
                    fx + fw - 92,
                    cy + 18,
                    88,
                    20,
                    english ? "Copy code" : "Code kopieren",
                    () -> Minecraft.getInstance().keyboardHandler.setClipboard(code)
            );

            copy.setTooltip(Tooltip.create(Component.literal(
                    tr("Copies the login code", "Kopiert den Anmeldecode")
            )));
        }
    }

    private void input(
            int x,
            int y,
            int w,
            String name,
            int max,
            String value,
            Consumer<String> responder
    ) {
        boxes.add(new Box(
                x - 1, y - 1,
                x + w + 1, y + 17,
                COL_INPUT_BORDER
        ));

        boxes.add(new Box(
                x, y,
                x + w, y + 16,
                COL_INPUT_BG
        ));

        EditBox e = new EditBox(
                this.font,
                x + 4,
                y,
                w - 8,
                16,
                Component.literal(name)
        );

        e.setBordered(false);
        e.setMaxLength(max);
        e.setValue(value);
        e.setResponder(responder);

        addRenderableWidget(e);
    }

    private void saveFields() {
        send("set", "channel=" + channelText);
        send("set", "clientId=" + clientIdText);
        send("set", "targetPlayer=" + targetText);
    }

    private int statusColor() {
        return switch (ClientState.twitch("state")) {
            case "CONNECTED" -> COL_OK;
            case "ERROR" -> COL_ERR;
            case "AUTHORIZING", "CONNECTING" -> COL_WARN;
            default -> COL_MUTED;
        };
    }

    // ------------------------------------------------------------
    // Seite 2: Ereignisse
    // ------------------------------------------------------------

    private void buildEvents(int y) {
        int fx = left + 10;
        int fw = pw - 20;
        int cw = (fw - 8) / 3;

        labels.add(new Label(
                tr(
                        "Which Twitch events trigger chaos?",
                        "Welche Twitch-Ereignisse loesen Chaos aus?"
                ),
                fx, y - 2, COL_MUTED
        ));

        boxes.add(new Box(
                fx - 4, y + 8,
                fx + fw + 4, y + 84,
                COL_CARD
        ));

        String[] names = {
                "Follow",
                "Sub",
                "Resub",
                "Gift Sub",
                "Bits",
                "Raid",
                english ? "Points" : "Kanalpunkte"
        };

        String[] keys = {
                "follow",
                "sub",
                "resub",
                "gift",
                "bits",
                "raid",
                "points"
        };

        String[] flags = {
                "enableFollow",
                "enableSub",
                "enableResub",
                "enableGift",
                "enableBits",
                "enableRaid",
                "enableChannelPoints"
        };

        for (int i = 0; i < names.length; i++) {
            int cx = fx + (i % 3) * (cw + 4);
            int cy = y + 12 + (i / 3) * 24;

            toggle(
                    names[i],
                    keys[i],
                    ClientState.flag(flags[i]),
                    cx, cy, cw
            );
        }

        Button donation = addButton(
                fx + (cw + 4),
                y + 12 + 2 * 24,
                cw,
                20,
                english ? "Donations: soon" : "Spenden: bald",
                () -> {}
        );

        donation.active = false;

        donation.setTooltip(Tooltip.create(Component.literal(
                tr(
                        "Donations are not integrated yet.",
                        "Spenden sind noch nicht angebunden."
                )
        )));

        boxes.add(new Box(
                fx - 4, y + 92,
                fx + fw + 4, y + 146,
                COL_CARD
        ));

        labels.add(new Label(
                tr("What happens?", "Was passiert?"),
                fx, y + 96, COL_ACCENT
        ));

        labels.add(new Label(
                tr(
                        "Follow: random event   |   Sub: monster or boss",
                        "Follow: Zufallsevent   |   Sub: Monster oder Boss"
                ),
                fx, y + 108, COL_TEXT
        ));

        labels.add(new Label(
                tr(
                        "Resub: stronger   |   Gift: reward or danger",
                        "Resub: verstaerkt   |   Gift: Belohnung oder Gefahr"
                ),
                fx, y + 120, COL_TEXT
        ));

        labels.add(new Label(
                tr(
                        "Bits: depends on amount   |   Raid: monster wave",
                        "Bits: je nach Anzahl   |   Raid: Monsterwelle"
                ),
                fx, y + 132, COL_TEXT
        ));
    }

    // ------------------------------------------------------------
    // Seite 3: Werte
    // ------------------------------------------------------------

    private void buildValues(int y) {
        int fx = left + 10;
        int fw = pw - 20;
        int cw = (fw - 8) / 3;

        labels.add(new Label(
                tr(
                        "Chances, cooldowns and limits",
                        "Wahrscheinlichkeiten, Cooldowns und Grenzen"
                ),
                fx, y - 2, COL_MUTED
        ));

        int r = y + 14;
        int step = 42;

        int[] col = {
                fx,
                fx + cw + 4,
                fx + 2 * (cw + 4)
        };

        number("Mindest-Bits", "minBits", "minBits", 50, "", col[0], r, cw);
        number("Event-Chance", "eventChancePercent", "chance", 5, " %", col[1], r, cw);
        number("Max. Monster/Event", "maxMobsPerEvent", "maxMobs", 5, "", col[2], r, cw);

        number("Gewicht Normal", "weightNormal", "wNormal", 5, "", col[0], r + step, cw);
        number("Gewicht Selten", "weightRare", "wRare", 5, "", col[1], r + step, cw);
        number("Gewicht Besonders", "weightSpecial", "wSpecial", 5, "", col[2], r + step, cw);

        number("Cooldown", "cooldownSeconds", "cooldown", 2, " s", col[0], r + 2 * step, cw);
        number("Cooldown Besonders", "specialCooldownSeconds", "specialCooldown", 10, " s", col[1], r + 2 * step, cw);
        number("Chaos-Intervall", "standaloneIntervalSeconds", "interval", 10, " s", col[2], r + 2 * step, cw);
    }

    private void number(
            String title,
            String stateKey,
            String adjustKey,
            int step,
            String unit,
            int x,
            int y,
            int w
    ) {
        boxes.add(new Box(
                x - 3, y - 3,
                x + w + 3, y + 35,
                COL_CARD
        ));

        labels.add(new Label(
                title, x + 2, y, COL_MUTED
        ));

        int value = ClientState.num(stateKey);

        Button minus = addButton(
                x, y + 12, 22, 20,
                "-",
                () -> send("adjust", adjustKey + ":" + (-step))
        );

        minus.setTooltip(Tooltip.create(Component.literal("-" + step + unit)));

        Button plus = addButton(
                x + w - 22, y + 12, 22, 20,
                "+",
                () -> send("adjust", adjustKey + ":" + step)
        );

        plus.setTooltip(Tooltip.create(Component.literal("+" + step + unit)));

        String shown = value + unit;

        labels.add(new Label(
                shown,
                x + w / 2 - this.font.width(shown) / 2,
                y + 18,
                COL_ACCENT
        ));
    }

    // ------------------------------------------------------------
    // Seite 4: Chaos
    // ------------------------------------------------------------

    private void buildChaos(int y) {
        int fx = left + 10;
        int fw = pw - 20;

        labels.add(new Label(
                tr(
                        "Simulate a Twitch event (no Twitch connection needed)",
                        "Twitch-Ereignis simulieren (ohne Twitch-Verbindung)"
                ),
                fx, y - 2, COL_MUTED
        ));

        String[] types = {
                "follow", "sub", "resub", "gift",
                "bits", "raid", "points"
        };

        String[] names = {
                "Follow", "Sub", "Resub", "Gift",
                "Bits", "Raid", english ? "Points" : "Punkte"
        };

        int bw = (fw - 18) / 7;

        for (int i = 0; i < types.length; i++) {
            final String type = types[i];

            addButton(
                    fx + i * (bw + 3),
                    y + 9,
                    bw,
                    20,
                    names[i],
                    () -> send("test", type)
            );
        }

        boolean on = ClientState.standalone();

        toggle(
                tr("Chaos mode", "Chaos-Modus"),
                "standalone",
                on,
                fx,
                y + 33,
                fw / 2 - 2
        );

        addButton(
                fx + fw / 2 + 2,
                y + 33,
                fw / 2 - 2,
                20,
                english ? "Random event now" : "Zufalls-Event jetzt",
                () -> send("event", "random")
        );

        List<JsonElement> events = new ArrayList<>();

        for (JsonElement el : ClientState.events()) {
            events.add(el);
        }

        int perPage = EVENT_COLS * EVENT_ROWS;

        int pages = Math.max(
                1,
                (events.size() + perPage - 1) / perPage
        );

        if (eventPage >= pages) {
            eventPage = pages - 1;
        }

        if (eventPage < 0) {
            eventPage = 0;
        }

        labels.add(new Label(
                tr(
                        "Trigger individual events:",
                        "Einzelne Events ausloesen:"
                ),
                fx, y + 59, COL_MUTED
        ));

        int gy = y + 70;

        boxes.add(new Box(
                fx - 4,
                gy - 3,
                fx + fw + 4,
                gy + EVENT_ROWS * 22 + 22,
                COL_CARD
        ));

        int ew = (fw - (EVENT_COLS - 1) * 3) / EVENT_COLS;
        int start = eventPage * perPage;

        for (int n = 0; n < perPage && start + n < events.size(); n++) {
            var o = events.get(start + n).getAsJsonObject();

            final String id = o.get("id").getAsString();
            String rarity = o.get("rarity").getAsString();

            int row = n / EVENT_COLS;
            int col = n % EVENT_COLS;

            String shown = this.font.plainSubstrByWidth(id, ew - 8);

            Button eventButton = addButton(
                    fx + col * (ew + 3),
                    gy + row * 22,
                    ew,
                    20,
                    shown,
                    () -> send("event", id)
            );

            eventButton.setTooltip(Tooltip.create(Component.literal(
                    id + " (" + rarity + ")"
            )));
        }

        int py = gy + EVENT_ROWS * 22 + 1;

        Button prev = addButton(
                fx, py, 20, 16,
                "<",
                () -> {
                    eventPage--;
                    rebuild();
                }
        );

        prev.active = eventPage > 0;

        Button next = addButton(
                fx + 24, py, 20, 16,
                ">",
                () -> {
                    eventPage++;
                    rebuild();
                }
        );

        next.active = eventPage < pages - 1;

        labels.add(new Label(
                (english ? "Page " : "Seite ")
                        + (eventPage + 1) + "/" + pages,
                fx + 50,
                py + 4,
                COL_TEXT
        ));

        String queue = (english ? "Queue: " : "Warteschlange: ")
                + ClientState.queue();

        labels.add(new Label(
                queue,
                fx + fw - this.font.width(queue),
                py + 4,
                COL_MUTED
        ));
    }

    // ------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor g,
            int mouseX,
            int mouseY,
            float delta
    ) {
        g.fill(
                left - 2, top - 2,
                left + pw + 2, top + ph + 2,
                COL_BORDER
        );

        g.fillGradient(
                left, top,
                left + pw, top + ph,
                COL_PANEL_TOP,
                COL_PANEL_BOTTOM
        );

        g.fillGradient(
                left, top,
                left + pw, top + 24,
                COL_HEADER_TOP,
                COL_HEADER_BOTTOM
        );

        g.fill(
                left, top + 24,
                left + pw, top + 25,
                COL_BORDER
        );

        for (Box b : boxes) {
            g.fill(
                    b.x1(), b.y1(),
                    b.x2(), b.y2(),
                    b.color()
            );
        }

        super.extractRenderState(g, mouseX, mouseY, delta);

        g.text(
                this.font,
                "TWITCH CHAOS",
                left + 10,
                top + 8,
                0xFFFFFFFF,
                true
        );

        g.fill(
                left + 94, top + 6,
                left + 95, top + 18,
                0x66FFFFFF
        );

        g.text(
                this.font,
                english ? "CONTROL PANEL" : "STEUERZENTRALE",
                left + 102,
                top + 8,
                COL_TEXT,
                false
        );

        String stateText = switch (ClientState.twitch("state")) {
            case "CONNECTED" -> english ? "Connected" : "Verbunden";
            case "ERROR" -> english ? "Error" : "Fehler";
            case "AUTHORIZING", "CONNECTING" -> english ? "Connecting" : "Verbinde";
            default -> english ? "Offline" : "Getrennt";
        };

        int sx = left + pw - 52 - this.font.width(stateText);

        g.fill(
                sx - 11, top + 8,
                sx - 3, top + 16,
                0xFF000000
        );

        g.fill(
                sx - 10, top + 9,
                sx - 4, top + 15,
                statusColor()
        );

        g.text(
                this.font,
                stateText,
                sx,
                top + 8,
                COL_TEXT,
                false
        );

        for (Label l : labels) {
            g.text(
                    this.font,
                    l.text(),
                    l.x(),
                    l.y(),
                    l.color(),
                    false
            );
        }
    }
}
