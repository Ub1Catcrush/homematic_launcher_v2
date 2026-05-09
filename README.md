# HomeMatic Launcher

> **Deutsch** | [English below](#english)

---

## Deutsch

Ein Android-Launcher für die **HomeMatic CCU** (CCU2/CCU3/RaspberryMatic), der Raum- und Gerätestatus direkt auf dem Homescreen anzeigt — optimiert für Wandtablets und Kiosk-Displays.

---

### Funktionen

#### Raumübersicht
- **Echtzeit-Rasterübersicht** aller HomeMatic-Räume mit Temperatur, Luftfeuchtigkeit und Fensterstatus
- **Farbige Fensterindikatoren**: Grün = geschlossen · Gold = gekippt · Rot = offen
- **Schimmelwarnung**: Automatische Berechnung des absoluten Feuchtigkeitsgehalts mit Blinksignal
- **LowBat / Sabotage / Störung**: Blinkendes Farb-Badge direkt in der Raumkachel
- **Etagensortierung**: UG → EG → OG → DG → Außenbereich
- **Detailansicht**: Tap auf Raumkachel öffnet Bottom-Sheet mit allen Datenpunkten, Zeitstempeln und Profil-Analyse (★ = nicht im Profil)
- **Thermostat-Slider**: Langer Druck auf Solltemperatur öffnet Slider-Dialog; optimistische UI-Aktualisierung ohne Warten auf nächsten Sync

#### Home Assistant (HA) Integration
- **Beliebig viele HA-Kacheln** konfigurierbar — jede Kachel hat eigenen Titel und eigene Datenpunkt-Liste
- **Echtzeit-WebSocket-Verbindung** zu Home Assistant (WS-API)
- **Entitäts-Auswahl mit Autocomplete**: Tippen für Vorschläge aus live HA-Datenpunkten, mit automatischer Befüllung von Name und Einheit
- **Datenpunkte umsortierbar**: ↑↓-Buttons in der Listenansicht jeder Kachel
- **Verbindungsstatus** direkt in der Kachel (Verbinde… / Authentifiziere… / Fehler)

#### Kamera
- **RTSP-Stream**: ExoPlayer mit automatischem Fallback auf Snapshot bei Verbindungsproblemen
- **MJPEG-Snapshot**: Polling-Fallback mit konfigurierbarem Intervall
- **Mute-Button**: Ton per Tap stummschalten/aktivieren (nur RTSP)
- **Skalierungsmodi**: Center Crop, Fit, Fill (einstellbar)

#### Bewegungserkennung
- **Zwei unabhängige Quellen**, getrennt aktivierbar:
  - **Webcam**: Analyiert den konfigurierten RTSP-Stream oder Snapshot-Feed per Pixel-Differenz-Algorithmus
  - **Gerätekamera**: Frontkamera oder Rückkamera des Android-Geräts via CameraX
- **Hintergrund-Betrieb**: Läuft als Foreground-Service weiter wenn Bildschirm aus oder App im Hintergrund
- **Bildschirm aus Standby wecken**: `FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP` weckt das Gerät physisch aus dem Tiefschlaf
- **Konfigurierbarer Timeout**: Bildschirm bleibt nach Bewegung/Touch für einstellbare Zeit aktiv (System-Timeout überschrieben)
- **Erkennungsbereich (ROI)**: Visueller Zonenpicker — per Drag-and-Drop Zone im Kamerabild festlegen
- **Aktivzeiten**: Bewegungserkennung nur in definierten Uhrzeitfenstern aktiv (z.B. nur nachts)
- **Algorithmus-Parameter** über Schieberegler einstellbar:
  - Empfindlichkeit (1–30 % geänderte Pixel)
  - Helligkeitsschwelle (1–80 Luma-Punkte)
  - Analyseintervall (1–10 s)
  - Sperrzeit zwischen Auslösungen (1–60 s)
  - Hintergrundanpassung (0–20, verhindert Fehlauslösungen bei Beleuchtungsänderungen)

#### Nacht-Dimmen
- **Automatisches Abdunkeln** in einstellbarem Zeitfenster (z.B. 22:00–07:00)
- **Helligkeit stufenlos einstellbar** (1–100 %, Empfehlung: 3–8 % für Nachtbetrieb)
- **Übernacht-Fenster** korrekt unterstützt (z.B. 22:00–07:00)

#### DB-Transit-Anzeige
- **Echtzeit-Abfahrtsboard** via `v6.db.transport.rest` oder eigener Self-Hosted-Instanz
- **4 Spalten**: Linie | Zeit | Verspätung/✓ | Umstieg-Info
- **Hin/Rück-Toggle**: Verbindungsrichtung per Tap umkehren
- **Mehrere Verbindungen**: Bis zu 4 konfigurierbare Strecken, per `‹ ›` durchschalten
- **Detailansicht**: Tap auf Verbindungszeile öffnet Bottom-Sheet mit Abfahrt/Ankunft je Etappe

#### Wetter
- **Open-Meteo-Integration**: Tagesvorschau, Temperatur-Hoch/Tief, Niederschlag, Icon
- **Anzeige-Modi**: Als Überlagerung über Kamera, als eigene Kachel im Raster oder deaktiviert

#### Layout & UI
- **Portrait**: 2 Spalten, DB-Panel + Kamera untereinander
- **Landscape**: 3–4 Spalten (volle Breite), DB + Kamera nebeneinander unten
- **Farbschema**: Hell, Dunkel oder Systemeinstellung — zur Laufzeit umschaltbar
- **Anpassbare Farben & Schriftgrößen**: Über Einstellungen → Erscheinungsbild
- **Launcher-Modus**: Als Standard-Home-App nutzbar (Kiosk)

#### Einstellungen & Sicherung
- **Thematisch gegliederte Einstellungen**: 6 Hauptkategorien (Verbindung · Anzeige · Smart Home · Kamera & Erkennung · Infodienste · System)
- **Schieberegler statt Texteingabe** für alle numerischen Parameter
- **Vollständiger Export/Import**: Alle Einstellungen als JSON-Datei sichern und wiederherstellen
- **Selektiver Import**: Beim Einspielen wählen welche Kategorien überschrieben werden (CCU-Zugangsdaten getrennt von Darstellungs-Einstellungen)
- **Einstellungs-Format v2** mit Zeitstempel und Kategorisierung, rückwärtskompatibel mit v1

---

### Einstellungen-Übersicht

Die Einstellungen sind in logische Bereiche aufgeteilt und über ⚙ in der Toolbar erreichbar:

| Bereich | Inhalt |
|---|---|
| **CCU Verbindung** | Host, Port, HTTPS, API-Pfad, SID, Timeout, Zertifikate |
| **Anzeige** | Farbschema, Bildschirm aktiv, Statusleiste, Raster-Spalten |
| **Erscheinungsbild** | Farben, Schriftgrößen, Overlay-Transparenz |
| **Home Assistant** | WebSocket-URL, Token, Kacheln verwalten (beliebig viele) |
| **Benachrichtigungen** | Fenster offen, LowBat, Sabotage, Hintergrund |
| **Kamera** | URLs, Zugangsdaten, Skalierung, Panel-Größe, Timeouts |
| **Bewegungserkennung & Nacht** | Quellen, ROI, Aktivzeiten, Timeout, Algorithmus-Parameter, Nacht-Dimmen |
| **Wetter** | Stadt/Koordinaten, Anzeigemodus, Aktualisierungsintervall |
| **ÖPNV** | Strecken, API-Server, Haltestellen, Intervall |
| **Erweitert** | Geräteprofil, Entwickleroptionen, Launcher |
| **Einstellungen sichern** | Export / Import mit Kategorieauswahl |

---

### Anforderungen

| | Minimum | Empfohlen |
|---|---|---|
| **Android** | 8.0 (API 26) | 12+ (API 32+) |
| **CCU** | CCU2 mit XML-API Add-on | CCU3 / RaspberryMatic |
| **Netzwerk** | WLAN im gleichen Subnetz | Lokales Netzwerk |

### XML-API Add-on

1. CCU-Weboberfläche → **Einstellungen** → **Add-on Software**
2. XML-API installieren: https://github.com/homematic-community/XML-API
3. Test: `http://<CCU-IP>/addons/xmlapi/devicelist.cgi`

### Installation

**APK direkt:**
1. Release-APK herunterladen
2. **Einstellungen → Sicherheit → Unbekannte Quellen** aktivieren
3. APK installieren, als Standard-Launcher festlegen (empfohlen für Tablet-Kiosk)

**Aus Quellcode:**
```bash
git clone <repository-url>
cd HomeMaticLauncher
./gradlew assembleRelease
```
Voraussetzungen: Android Studio Hedgehog+, JDK 17, Android SDK 36

### Bewegungserkennung einrichten

1. **Einstellungen → Bewegungserkennung & Nacht**
2. **Quelle wählen**: Webcam (braucht konfigurierte Kamera-URL) und/oder Gerätekamera
3. Gerätekamera: Kamera-Berechtigung wird beim ersten Aktivieren angefragt
4. **Erkennungsbereich** (ROI): Per Zonenpicker den relevanten Bildbereich einzeichnen — z.B. nur Eingangsbereich, nicht das Fenster das sich im Wind bewegt
5. **Empfindlichkeit** auf 8 belassen für den Einstieg, dann je nach Fehlauslösungsrate anpassen
6. **Aktivzeiten** setzen wenn die Erkennung nur nachts aktiv sein soll
7. App läuft auch bei gesperrtem Bildschirm weiter — eine persistente Benachrichtigung zeigt den Dienst an

### Bekannte Einschränkungen

- **Bildschirm ausschalten** per App erfordert Geräteadministrator-Rechte (noch nicht implementiert; die App zeigt stattdessen einen Hinweis)
- **HTTPS mit selbstsigniertem Zertifikat**: Option „Selbstsignierte Zertifikate akzeptieren" in den Einstellungen aktivieren
- Der öffentliche `v6.db.transport.rest`-Server hat ein niedriges Rate-Limit und kann unter Last 503 zurückgeben; Self-Hosting via Docker wird für zuverlässigen Betrieb empfohlen

---

### Projektstruktur

```
app/src/main/
├── java/com/tvcs/homematic/
│   ├── MainActivity.kt                — Hauptscreen, Lifecycle, Netzwerk, Service-Orchestrierung
│   ├── HomeMatic.kt                   — CCU-Abruf (parallel), XML-Parsing, Schimmelberechnung
│   ├── HmRepository.kt                — Interface + FakeHmRepository (Tests)
│   ├── RoomAdapter.kt                 — Raumkacheln, Multi-Kachel-Support (Wetter, HA, Transit)
│   ├── HaTileViewController.kt        — HA-Kacheln, Multi-Instanz, HaTileConfig
│   ├── HaRepository.kt                — HA WebSocket-Client, StateFlow, Reconnect
│   ├── CameraViewController.kt        — RTSP/Snapshot, Mute, Fallback, PixelCopy-Sampling
│   ├── MotionDetectionEngine.kt        — Pixel-Diff-Algorithmus, ROI, Adaption, Zeitfenster
│   ├── MotionDetectionService.kt       — Foreground-Service für Hintergrund-Erkennung
│   ├── MotionPrefsHelper.kt            — Einstellungen → Engine-Konfiguration
│   ├── LocalCameraMotionSource.kt      — CameraX-Integration für Gerätekamera
│   ├── ScreenWakeController.kt         — WakeLock, Window-Flags, Timeout-Timer
│   ├── NightDimController.kt           — Uhrzeitbasiertes Display-Dimmen
│   ├── RoiPickerPreference.kt          — Visueller Zonenpicker (Custom Preference)
│   ├── SeekBarPreference.kt            — Schieberegler-Preference
│   ├── WeatherViewController.kt        — Open-Meteo, Kachel/Overlay
│   ├── DbTransitViewController.kt      — Transit-Panel, 4-Spalten-Layout
│   ├── SettingsActivity.kt             — Alle Einstellungs-Fragments
│   ├── ProfileExportImport.kt          — JSON-Export/Import mit Kategorien
│   ├── AppThemeHelper.kt               — Farben, Schriftgrößen, Theme
│   └── PreferenceKeys.kt               — Alle Preference-Keys als Konstanten
└── res/
    ├── xml/preferences_motion.xml      — Bewegungserkennung & Nacht (eigene Seite)
    ├── xml/preferences_camera.xml      — Kamera (ohne Motion)
    ├── xml/preferences_main.xml        — Navigation mit 6 Kategorien
    ├── layout/pref_seekbar.xml         — SeekBar-Preference-Layout
    ├── values/                          — Deutsche Strings (Standard)
    └── values-en/                       — Englische Strings
```

### Technologie

| | |
|---|---|
| Sprache | Kotlin (JVM 17) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 (Android 16) |
| Build | Gradle 8.x + AGP 8.x |
| Async | Kotlin Coroutines + StateFlow |
| Video | ExoPlayer / Media3 |
| Kamera | CameraX 1.4 |
| UI | Material3 DayNight |
| HA | WebSocket (OkHttp) |
| Transit | db-rest REST API |
| Wetter | Open-Meteo (kostenlos, kein API-Key) |

---

### Changelog

#### v2.2.4 (aktuell)
- **Bewegungserkennung aus Standby wecken**: `FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP` im Foreground-Service weckt das Gerät physisch; `FLAG_KEEP_SCREEN_ON` hält es aktiv
- **Vollständige Stabilität**: Alle Coroutinen mit korrektem `CancellationException`-Handling; alle Dialog-Aufrufe mit `isResumed`-Guard; alle Broadcast-/NetworkCallback-Pfade mit `isFinishing`/`isDestroyed`-Guard
- **Neue Bewegungserkennungs-UX**: Eigene Einstellungsseite, Schieberegler für alle Parameter, visueller ROI-Zonenpicker per Drag-and-Drop
- **Einstellungs-Navigation neu gegliedert**: 6 logische Hauptkategorien
- **HA-Datenpunkte umsortierbar**: ↑↓-Buttons in allen Entity-Dialogen
- **Export/Import komplett überarbeitet**: Kategorieauswahl beim Export und Import, Format v2 mit Zeitstempel

#### v2.2.0
- **Beliebig viele HA-Kacheln**: Multi-Kachel-Unterstützung mit eigenem Kachel-Manager-Dialog
- **HA Entity Autocomplete**: Live-Vorschläge aus HA-Datenpunkten, automatische Befüllung von Name und Einheit
- **Bewegungserkennung**: Zwei Quellen (Webcam + Gerätekamera), ROI, Aktivzeiten, adaptive Hintergrundanpassung, Nacht-Dimmen
- **Foreground-Service**: Bewegungserkennung läuft auch bei gesperrtem Bildschirm

#### v2.1.0
- `HmRepository`-Interface, DB Transit komplett, Transit-Detail-Sheet, Kamera-Mute, Launcher-Switch, Landscape-Layout, Wetter-Kachel, Thermostat-Slider, paralleles CCU-Laden, Backoff

#### v2.0.0
- Vollständige Java → Kotlin Migration, AsyncTask → Coroutines, min SDK 26, Material3

#### v1.0
- Java, AsyncTask, Basis-Raum-/Temperatur-/Fensterstatus-Anzeige

---

<a name="english"></a>

## English

An Android launcher for the **HomeMatic CCU** (CCU2/CCU3/RaspberryMatic) displaying room and device status directly on the home screen — optimised for wall tablets and kiosk displays.

---

### Features

#### Room Overview
- **Real-time grid** of all HomeMatic rooms with temperature, humidity and window status
- **Colour-coded window indicators**: Green = closed · Gold = tilted · Red = open
- **Mould warning**: Automatic absolute humidity calculation with blinking alert
- **LowBat / Sabotage / Fault**: Blinking colour badge directly in the room tile
- **Floor sorting**: Basement → Ground → Upper → Attic → Outdoor
- **Detail view**: Tap a room tile to open a bottom sheet with all data points, timestamps and profile analysis (★ = not in profile)
- **Thermostat slider**: Long-press the set-temperature row for a slider dialog; optimistic UI update without waiting for the next sync

#### Home Assistant (HA) Integration
- **Unlimited HA tiles** — each tile has its own title and entity list
- **Real-time WebSocket connection** to Home Assistant
- **Entity picker with autocomplete**: Type to get live suggestions from HA, with automatic name and unit fill-in
- **Drag-to-reorder entities**: ↑↓ buttons on every row
- **Connection status** shown inside the tile (Connecting… / Authenticating… / Error)

#### Camera
- **RTSP stream**: ExoPlayer with automatic fallback to snapshot on connection failure
- **MJPEG snapshot**: Polling fallback with configurable interval
- **Mute button**: Silence/restore audio with a tap (RTSP only)
- **Scale modes**: Center Crop, Fit, Fill

#### Motion Detection
- **Two independent sources**, individually activatable:
  - **Webcam**: Analyses the configured RTSP stream or snapshot feed via pixel-difference algorithm
  - **Device camera**: Front or rear camera of the Android device via CameraX
- **Background operation**: Runs as a Foreground Service when the screen is off or the app is in the background
- **Wake from standby**: `FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP` physically wakes the device from deep sleep
- **Configurable timeout**: Screen stays on after motion/touch for a set duration (overrides system display timeout)
- **Detection zone (ROI)**: Visual zone picker — drag to draw the active area in the camera frame
- **Active time window**: Motion detection only active within configured hours (e.g. night-only)
- **Algorithm parameters** via sliders:
  - Sensitivity (1–30 % changed pixels)
  - Brightness threshold (1–80 luma points)
  - Analysis interval (1–10 s)
  - Cooldown between triggers (1–60 s)
  - Background adaptation (0–20, prevents false triggers on lighting changes)

#### Night Dimming
- **Automatic dimming** within a configurable time window (e.g. 22:00–07:00)
- **Brightness adjustable** from 1–100 % (recommended: 3–8 % for night operation)
- **Overnight windows** correctly supported (e.g. 22:00–07:00)

#### DB Transit Display
- **Real-time departure board** via `v6.db.transport.rest` or a self-hosted instance
- **4 columns**: Line | Time | Delay/✓ | Transfer info
- **Outward/Return toggle**: Flip direction with a tap
- **Multiple connections**: Up to 4 configurable routes, switchable via `‹ ›`
- **Detail view**: Tap a departure row for a bottom sheet with per-leg departure and arrival

#### Weather
- **Open-Meteo integration**: Daily forecast, high/low temperature, precipitation, icon
- **Display modes**: Overlay on camera, tile in the room grid, or disabled

#### Layout & UI
- **Portrait**: 2 columns, DB panel + camera stacked
- **Landscape**: 3–4 columns (full width), DB + camera side by side
- **Colour scheme**: Light, Dark, or System — switchable at runtime
- **Customisable colours & font sizes**: via Settings → Appearance
- **Launcher mode**: Can be set as the default home app (kiosk)

#### Settings & Backup
- **Logically organised settings**: 6 main categories (Connection · Display · Smart Home · Camera & Detection · Info Services · System)
- **Sliders instead of text fields** for all numeric parameters
- **Full Export/Import**: All settings as a JSON file
- **Selective import**: Choose which categories to restore (keep CCU credentials, restore only appearance)
- **Settings format v2** with timestamp and categories, backwards-compatible with v1

---

### Settings Overview

Access via ⚙ in the toolbar:

| Section | Contents |
|---|---|
| **CCU Connection** | Host, port, HTTPS, API path, SID, timeout, certificates |
| **Display** | Colour scheme, keep screen on, status bar, grid columns |
| **Appearance** | Colours, font sizes, overlay opacity |
| **Home Assistant** | WebSocket URL, token, tile manager (unlimited tiles) |
| **Notifications** | Window open, LowBat, sabotage, background |
| **Camera** | URLs, credentials, scale mode, panel size, timeouts |
| **Motion Detection & Night** | Sources, ROI, schedule, timeout, algorithm params, night dim |
| **Weather** | City/coordinates, display mode, refresh interval |
| **Public Transit** | Routes, API server, stops, interval |
| **Advanced** | Device profile, developer options, launcher |
| **Backup Settings** | Export / Import with category selection |

---

### Requirements

| | Minimum | Recommended |
|---|---|---|
| **Android** | 8.0 (API 26) | 12+ (API 32+) |
| **CCU** | CCU2 with XML-API add-on | CCU3 / RaspberryMatic |
| **Network** | Wi-Fi on the same subnet | Local network |

### XML-API Add-on

1. CCU web UI → **Settings** → **Add-on software**
2. Install XML-API: https://github.com/homematic-community/XML-API
3. Test: `http://<CCU-IP>/addons/xmlapi/devicelist.cgi`

### Installation

**Direct APK:**
1. Download the release APK
2. Enable **Settings → Security → Unknown sources**
3. Install and optionally set as default launcher (recommended for tablet kiosk)

**From source:**
```bash
git clone <repository-url>
cd HomeMaticLauncher
./gradlew assembleRelease
```
Requirements: Android Studio Hedgehog+, JDK 17, Android SDK 36

### Motion Detection Setup

1. **Settings → Motion Detection & Night**
2. **Choose source(s)**: Webcam (requires camera URL) and/or device camera
3. Device camera: Camera permission is requested on first enable
4. **Detection zone** (ROI): Use the zone picker to draw the relevant area — e.g. doorway only, not the window that moves in wind
5. **Sensitivity**: Start at 8, then adjust based on false trigger rate
6. **Active times**: Set if detection should only run at night
7. The app continues running with a persistent notification when the screen is locked

### Known Limitations

- **Screen off** via app requires Device Admin rights (not yet implemented; the app shows a hint instead)
- **HTTPS with self-signed certificate**: Enable "Accept self-signed certificates" in settings
- The public `v6.db.transport.rest` server has a low rate limit and may return 503 under load; self-hosting via Docker is recommended for reliable operation

---

### Project Structure

```
app/src/main/
├── java/com/tvcs/homematic/
│   ├── MainActivity.kt                — Main screen, lifecycle, network, service orchestration
│   ├── HomeMatic.kt                   — CCU fetch (parallel), XML parsing, mould calculation
│   ├── HmRepository.kt                — Interface + FakeHmRepository (tests)
│   ├── RoomAdapter.kt                 — Room tiles, multi-tile support (weather, HA, transit)
│   ├── HaTileViewController.kt        — HA tiles, multi-instance, HaTileConfig
│   ├── HaRepository.kt                — HA WebSocket client, StateFlow, reconnect
│   ├── CameraViewController.kt        — RTSP/snapshot, mute, fallback, PixelCopy sampling
│   ├── MotionDetectionEngine.kt        — Pixel-diff algorithm, ROI, adaptation, time window
│   ├── MotionDetectionService.kt       — Foreground service for background detection
│   ├── MotionPrefsHelper.kt            — Settings → engine configuration
│   ├── LocalCameraMotionSource.kt      — CameraX integration for device camera
│   ├── ScreenWakeController.kt         — WakeLock, window flags, inactivity timer
│   ├── NightDimController.kt           — Time-based display dimming
│   ├── RoiPickerPreference.kt          — Visual zone picker (custom Preference)
│   ├── SeekBarPreference.kt            — Slider preference
│   ├── WeatherViewController.kt        — Open-Meteo, tile/overlay
│   ├── DbTransitViewController.kt      — Transit panel, 4-column layout
│   ├── SettingsActivity.kt             — All settings fragments
│   ├── ProfileExportImport.kt          — JSON export/import with categories
│   ├── AppThemeHelper.kt               — Colours, font sizes, theme
│   └── PreferenceKeys.kt               — All preference keys as constants
└── res/
    ├── xml/preferences_motion.xml      — Motion detection & night (own page)
    ├── xml/preferences_camera.xml      — Camera (without motion)
    ├── xml/preferences_main.xml        — Navigation with 6 categories
    ├── layout/pref_seekbar.xml         — SeekBar preference layout
    ├── values/                          — German strings (default)
    └── values-en/                       — English strings
```

### Tech Stack

| | |
|---|---|
| Language | Kotlin (JVM 17) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 (Android 16) |
| Build | Gradle 8.x + AGP 8.x |
| Async | Kotlin Coroutines + StateFlow |
| Video | ExoPlayer / Media3 |
| Camera | CameraX 1.4 |
| UI | Material3 DayNight |
| HA | WebSocket (OkHttp) |
| Transit | db-rest REST API |
| Weather | Open-Meteo (free, no API key) |

---

### Changelog

#### v2.2.4 (current)
- **Wake from standby**: `FULL_WAKE_LOCK + ACQUIRE_CAUSES_WAKEUP` in the Foreground Service physically wakes the device; `FLAG_KEEP_SCREEN_ON` keeps it awake
- **Full stability pass**: All coroutines with correct `CancellationException` propagation; all dialog calls guarded by `isResumed`; all broadcast/NetworkCallback paths guarded by `isFinishing`/`isDestroyed`
- **New motion detection UX**: Dedicated settings page, sliders for all parameters, visual ROI zone picker
- **Settings navigation reorganised**: 6 logical main categories
- **HA entity reordering**: ↑↓ buttons in all entity list dialogs
- **Export/Import redesigned**: Category selection on export and import, format v2 with timestamp

#### v2.2.0
- **Unlimited HA tiles**: Multi-tile support with tile manager dialog
- **HA entity autocomplete**: Live suggestions from HA with automatic name and unit fill
- **Motion detection**: Two sources (webcam + device camera), ROI, time windows, adaptive background, night dimming
- **Foreground service**: Motion detection continues with screen locked

#### v2.1.0
- `HmRepository` interface, full DB Transit, transit detail sheet, camera mute, launcher switch, landscape layout, weather tile, thermostat slider, parallel CCU loading, backoff

#### v2.0.0
- Full Java → Kotlin migration, AsyncTask → Coroutines, min SDK 26, Material3

#### v1.0
- Java, AsyncTask, basic room/temperature/window display
