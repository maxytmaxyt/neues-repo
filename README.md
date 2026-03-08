# Discord TempChannel Bot

Ein Discord-Bot, der ein TeamSpeak-ähnliches System für temporäre Sprachkanäle bereitstellt – entwickelt in Java mit JDA 6.

---

## Projektstruktur

```
src/main/java/de/novium/dev/
├── BotMain.java                    # Einstiegspunkt, verdrahtet alle Komponenten
├── config/
│   └── BotConfig.java              # Typisierter Wrapper um BotProperties
├── tempchannel/
│   ├── TempChannelType.java        # Enum: 1er / 2er / 3er / 5er Talk
│   ├── TempChannelData.java        # Thread-sicherer Zustandscontainer pro Kanal
│   └── TempChannelService.java     # Kernlogik: Erstellen, Löschen, Lock/Unlock
├── listener/
│   ├── VoiceStateListener.java     # JDA-Event-Listener für Join/Leave
│   └── InteractionListener.java    # Buttons, Select-Menüs, Modals
├── panel/
│   └── PanelManager.java           # Erstellt/aktualisiert/löscht Panel-Nachrichten
└── db/
    └── Database.java               # SQLite-Persistenz
```

---

## Funktionsweise des Tempchannel-Systems

Beim Start erstellt der Bot für jeden der vier Typen (1er, 2er, 3er, 5er Talk) **genau einen leeren Kanal** in der ersten konfigurierten Kategorie.

**Wenn ein Nutzer einem leeren Kanal beitritt:**
1. Der Kanal wird *aktiviert* (der Nutzer wird zum Ersteller).
2. Sofern kein weiterer freier Kanal des selben Typs existiert, wird sofort ein neuer erstellt.
3. Eine Panel-Nachricht im konfigurierten Panel-Textkanal erscheint.

**Wenn ein Kanal wieder leer wird:**
1. Existiert bereits ein anderer freier Kanal desselben Typs → der geleerte Kanal wird gelöscht.
2. Kein anderer freier Kanal vorhanden → der Kanal bleibt als neuer Idle-Kanal erhalten.

Verlässt der **Ersteller** den Kanal, während noch andere drin sind, wird die Ownership automatisch auf das nächste Mitglied übertragen.

---

## Multithreading-Konzept

| Schicht | Mechanismus | Zweck |
|---|---|---|
| JDA Event Thread | unberührt | Empfängt Events, leitet sofort weiter |
| `TempChannelService.executor` | `FixedThreadPool(4)` | Führt alle Discord-API-Calls und Zustandsänderungen aus |
| `typeLocks` | `ReentrantLock` pro Typ | Verhindert doppelte Kanal-Erstellung / -Löschung |
| `TempChannelData.activated` | `AtomicBoolean.compareAndSet` | Zusätzlicher Schutz: nur ein Thread „gewinnt" die Aktivierung |
| `channels` Registry | `ConcurrentHashMap` | Threadsicheres Lesen/Schreiben der Kanalzustände |

Der JDA-Event-Thread wird **niemals blockiert** – alle zeitaufwändigen Operationen (REST-Calls, Locks) laufen auf dem separaten Worker-Pool.

---

## Lock/Unlock-Mechanismus

**Sperren:**
1. Alle aktuell im Kanal befindlichen Mitglieder werden auf die **Whitelist** gesetzt.
2. Die `@everyone`-Rolle erhält eine `DENY VOICE_CONNECT`-Override.
3. Jedes Whitelist-Mitglied erhält eine individuelle `GRANT VOICE_CONNECT`-Override.

**Entsperren:**
1. Die `@everyone`-Override wird entfernt.
2. Alle individuellen Member-Overrides werden entfernt.
3. Die Whitelist wird geleert.

Nur der **Ersteller** (Creator) des Kanals darf sperren/entsperren.  
Optional können Rollen in `bypass.role.ids` konfiguriert werden, die immer joinen dürfen.

---

## Konfiguration

Kopiere `bot.properties.example` als `bot.properties` neben die `bot.jar`:

```properties
token=YOUR_BOT_TOKEN_HERE
guild.id=123456789012345678
panel.channel.id=123456789012345678
categories=CAT_ID_1,CAT_ID_2
category.max.channels=45

# Optional: Rollen, die auch in gesperrte Kanäle joinen dürfen
# bypass.role.ids=ROLE_ID_1,ROLE_ID_2
```

---

## Build & Ausführung

**Voraussetzungen:** Java 21, Internet-Zugang (für Gradle-Download)

```bash
# Projekt bauen
./gradlew shadowJar

# Bot starten (bot.properties im gleichen Verzeichnis)
java -jar build/libs/bot.jar
```

---

## Panel-Buttons im Überblick

| Button | Funktion |
|---|---|
| 🔒 Sperren / 🔓 Entsperren | Kanal sperren (Whitelist-Logik aktiv) oder entsperren |
| 👑 Ownership | Ersteller-Rolle an ein anderes Kanalmitglied übergeben |
| 👢 Nutzer kicken | Mitglied aus dem Kanal entfernen (via Select-Menü) |
| 🔢 Limit ändern | Nutzer-Limit des Kanals anpassen (1–99, via Modal) |

Alle Panel-Interaktionen sind **ephemeral** – nur der Ersteller sieht die Antworten.

---

## Bonus-Features (implementiert)

- ✅ Automatische **Ownership-Übertragung**, wenn der Ersteller den Kanal verlässt
- ✅ **Kick-Button** im Panel (Select-Menü mit aktuellen Kanalmitgliedern)
- ✅ **Limit-Änderung** via Modal
- ✅ **SQLite-Persistenz** – Kanalzustand wird nach Bot-Neustart wiederhergestellt
- ✅ **Kategorie-Overflow** – automatischer Wechsel auf nächste Kategorie wenn voll
- ✅ **Bypass-Rollen** – konfigurierbare Rollen mit permanentem Join-Recht
