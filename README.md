# Twitch Chaos – Minecraft-Mod für Fabric

Twitch Chaos verbindet Minecraft mit Twitch: Follows, Subs, Bits und Raids lösen **echte Chaos-Events** im Spiel aus (Monster, Effekte, Blitze, Belohnungen, Bosskämpfe …). Die Mod funktioniert auch **ohne Twitch** (Befehle, Menü, Chaos-Modus) und ist nicht auf einen bestimmten Kanal zugeschnitten.

> ## Ehrlicher Status (bitte zuerst lesen)
>
> Dieses Projekt wurde in einer Umgebung geschrieben, in der **kein Java 25 und kein Zugriff auf Fabrics Maven-Server** vorhanden waren. Deshalb gilt:
>
> | Teil | Status |
> |---|---|
> | Twitch-Anbindung (`twitch/`) und Konfiguration (`config/`) | **Mit `javac` kompiliert** (ohne Fehler). Nicht gegen echtes Twitch getestet. |
> | Alle Minecraft-Teile (Events, Befehle, Netzwerk, Menü) | **Nicht kompiliert und nicht im Spiel getestet.** Syntax geprüft, Klassennamen gegen die Fabric-Dokumentation für 26.x abgeglichen. |
> | `.jar`-Datei | **Nicht erstellt** (Build war hier nicht möglich). |
>
> Minecraft 26.x verändert Klassen- und Methodennamen von Version zu Version. Es ist gut möglich, dass beim ersten Build ein paar Fehler kommen (Meldung lautet meist `cannot find symbol`). Das ist normal und meist mit einer Umbenennung behoben – **schicke mir die Fehlermeldung, ich korrigiere sie.**
>
> Am ehesten betroffen: `ChaosScreen` (Menü-Zeichnen), `EventContext` (`createCommandSourceStack`, `withLevel`, `withPosition`, `withSuppressedOutput`), `Perm` (`isSingleplayer`) und die Loom-Version in `gradle.properties`.

## Verwendete Versionen

Aus Quellen geprüft (Fabric-Downloadseiten, Fabric-Dokumentation):

- Minecraft **26.3**, Fabric Loader **0.19.5**, Fabric API **0.165.0+26.3**
- **Java 25** (ab Minecraft 26.1 Pflicht)
- Minecraft 26.x ist **unobfuskiert**: Plugin-ID `net.fabricmc.fabric-loom`, Abhängigkeiten per `implementation`
- Gradle **9.5.1** und Loom **1.17-SNAPSHOT**: Fabric empfiehlt diese Versionen für 26.2. Für 26.3 konnte ich keine eigene Empfehlung finden – falls der Build meckert, die aktuellen Werte auf <https://fabricmc.net/develop/> nachsehen und in `gradle.properties` bzw. `gradle/wrapper/gradle-wrapper.properties` eintragen.

## Kompilieren

1. **JDK 25** installieren (z. B. Temurin 25) und **IntelliJ IDEA 2025.3 oder neuer** verwenden.
2. Projektordner in IntelliJ öffnen („Open“ → Ordner `twitch-chaos`). IntelliJ erkennt Gradle automatisch. Unter *Settings → Build Tools → Gradle* das **Gradle JVM auf JDK 25** stellen.
3. Im Terminal (oder per Gradle-Fenster):
   ```
   ./gradlew build        (Windows: gradlew.bat build)
   ```
4. Die fertige Mod liegt in `build/libs/twitchchaos-1.0.0.jar` (die Datei **ohne** `-sources`).

Zum Testen im Spiel: `./gradlew runClient` startet Minecraft mit der Mod.

## Installieren

1. Minecraft 26.3 mit **Fabric Loader 0.19.5+** installieren (Fabric-Installer).
2. **Fabric API 0.165.0+26.3** in den `mods`-Ordner legen.
3. `twitchchaos-1.0.0.jar` ebenfalls in `mods` legen.
4. **Multiplayer:** Die Mod muss auf **Server und Clients** installiert sein. Die Twitch-Verbindung läuft auf dem Server (im Einzelspieler also lokal bei dir).

Einstellungen liegen in `config/twitchchaos.json`, die Twitch-Anmeldung getrennt in `config/twitchchaos_auth.json` (**niemals weitergeben oder zeigen, auch nicht im Stream!**).

## Mit Twitch verbinden

Die Mod nutzt die offizielle **Twitch EventSub API (WebSocket)** und die Anmeldung per **Device Code Flow**. Dafür brauchst du keine Passwörter und kein Client-Secret – nur eine eigene Twitch-Anwendung:

1. Auf <https://dev.twitch.tv/console> anmelden (Zwei-Faktor-Anmeldung muss aktiv sein) → **Anwendung registrieren**.
2. Name frei wählen, OAuth-Weiterleitungs-URL: `http://localhost`, Kategorie z. B. „Application Integration“, **Client-Typ: „Öffentlich“ (Public)**.
3. Die **Client-ID** kopieren (die Client-ID ist kein Geheimnis, ein Secret brauchst du nicht).
4. In Minecraft: `/twitchchaos gui` → Seite **Verbindung**:
   - Client-ID einfügen, optional Kanalname und Ziel-Spieler, **Speichern**.
   - **Verbinden** klicken. Es erscheint eine Adresse und ein Code.
   - Adresse im Browser öffnen, Code eingeben und mit deinem Twitch-Konto bestätigen.
5. Der Status wechselt auf „Verbunden“. Mit **Follow simulieren** (Seite *Chaos*) kannst du sofort testen.

Die Verbindung wird bei Ausfällen automatisch neu aufgebaut (Wartezeit 2–60 Sekunden). Mit „Beim Start automatisch verbinden“ geschieht das auch beim Serverstart.

### Wichtige Einschränkungen von Twitch

- **Subs, Resubs, Gift-Subs, Bits, Kanalpunkte** liefert Twitch nur für den **eigenen Kanal** (du meldest dich als Streamer an). **Raids** funktionieren für jeden Kanal. **Follows** brauchen Streamer- oder Moderator-Rechte.
- Nicht verfügbare Ereignisse nennt die Statuszeile („nicht verfügbar: …“). Der Rest läuft trotzdem.
- **Donations** sind kein Twitch-Standardereignis. Es ist **keine** Donation-Anbindung enthalten; im Menü ist sie deshalb ausdrücklich als „nicht verfügbar“ markiert.

## Befehle

| Befehl | Wirkung |
|---|---|
| `/twitchchaos help` | Hilfe (für alle) |
| `/twitchchaos gui` | Menü öffnen |
| `/twitchchaos start` / `stop` | Chaos-Modus ohne Twitch: alle X Sekunden ein Zufallsevent |
| `/twitchchaos event random` / `event <id>` | Event sofort auslösen (z. B. `event mimic`) |
| `/twitchchaos test follow\|sub\|resub\|gift\|bits\|raid\|points` | Twitch-Ereignis simulieren |
| `/twitchchaos connect` / `disconnect` / `status` | Twitch-Verbindung |
| `/twitchchaos reload` / `events` | Konfiguration neu laden / Event-Liste |

Außer `help` dürfen alle Befehle nur Operatoren (Stufe 2, wie `/gamemode`); im Einzelspieler immer der Spieler selbst.

## Welches Twitch-Ereignis macht was?

| Twitch | Minecraft |
|---|---|
| Follow, Kanalpunkte | Zufallsevent (gewichtet nach Normal / Selten / Besonders) |
| Sub | Stufe 1: Mimic oder Mini-Boss, Stufe 2: Mini-Boss, Stufe 3: Chaos-Titan |
| Resub | Seltenes Event, ab 12 Monaten ein besonderes |
| Gift Sub | Belohnung oder Gefahr; ab 5 Geschenken Jackpot |
| Bits | ab *Mindest-Bits*: normal, ab 500 selten, ab 1000 besonders, ab 5000 Bosskampf |
| Raid | Monsterwelle, größer mit mehr Zuschauern (bis „Max. Monster/Event“) |

**Events (IDs):** Normal: `zombie spiders skeletons creepers chickens effect lightning teleport cage moon darkness hunger` · Selten: `mimic wave miniboss reward danger piglins` · Besonders: `boss invasion jackpot blessing`.

Hinweise: Der *Eisenkäfig* ersetzt nur Luftblöcke und wird nach 10 Sekunden wieder entfernt. Monster werden im Umkreis von 3–7 Blöcken beschworen; an engen Stellen können sie dadurch ungünstig stehen.

## Sicherheit und Multiplayer

- Cooldowns, Chancen, Warteschlange und Berechtigungen werden **ausschließlich auf dem Server** geprüft. Das Menü sendet nur Wünsche.
- Jede Twitch-Nachricht hat eine ID; **doppelte Nachrichten werden verworfen**. Bei Cooldown werden Ereignisse in eine Warteschlange gestellt (Standard: 25).
- **Twitch-Texte werden nie als Minecraft-Befehl ausgeführt.** Events laufen über feste, im Code definierte Befehle; eingesetzt werden nur Zahlen und der geprüfte Spielername des Ziels. Twitch-Namen erscheinen nur als Chat-Text (bereinigt, max. 25 Zeichen).
- Einstellungen lassen sich nur über eine feste Liste von Schlüsseln ändern, Werte werden begrenzt.
- Tokens liegen nur in `config/twitchchaos_auth.json` (unter Linux/macOS mit Rechten `600`), nie im Quellcode oder Log.

## Projektstruktur

```
build.gradle · settings.gradle · gradle.properties · gradlew(.bat)
src/main/resources/fabric.mod.json
src/main/java/dev/twitchchaos/
  TwitchChaosMod.java          Einstieg, Server-Lebenszyklus
  config/ChaosConfig.java      Einstellungen (JSON, Whitelist)
  twitch/TwitchAuth.java       Device-Code-Anmeldung, Token
  twitch/TwitchService.java    EventSub-WebSocket, Reconnect, Deduplizierung
  twitch/TwitchEvent.java      Ereignis-Datentyp
  core/ChaosEvents.java        Alle Events
  core/ChaosManager.java       Warteschlange, Cooldowns, Zielwahl
  core/EventContext.java       Hilfen für Events (sichere interne Befehle)
  net/ …                       Pakete Menü <-> Server
  command/ChaosCommand.java    /twitchchaos
  client/ …                    Menü (dunkel, lila)
```

## Bekannte Grenzen

- Das Menü zeichnet sein Panel hinter die Widgets; Minecrafts Standard-Hintergrund dunkelt es leicht ab, die Farben wirken daher etwas gedämpfter als im Code angegeben.
- Eigene Entities, Modelle oder Sounds gibt es nicht: „Mimic“, „Mini-Boss“ und „Chaos-Titan“ sind benannte, verstärkte Vanilla-Monster.
- Monster-Daten (Name, Leben, Ausrüstung) werden per Spielbefehl gesetzt. Falls eine Minecraft-Version deren Schreibweise ändert, greift ein Ersatz mit schlichtem Namen (im Log steht dann „Befehl fehlgeschlagen“).
- Keine Donation-Anbindung (siehe oben).
