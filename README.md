# HomeMatic Launcher

> **Deutsch** | [English below](#english)

---

## Deutsch

Ein Android-Launcher für die **HomeMatic CCU** (CCU2/CCU3/RaspberryMatic), der Raum- und Gerätestatus direkt auf dem Homescreen anzeigt — optimiert für Wandtablets und Kiosk-Displays.

### Funktionen

#### Raumübersicht
- **Echtzeit-Übersicht** aller HomeMatic-Räume mit Temperatur, Luftfeuchtigkeit und Fensterstatus
- **Farbige Fensterindikatoren**: Grün = geschlossen · Gold = gekippt · Rot = offen
- **Schimmelwarnung**: Automatische Berechnung des absoluten Feuchtigkeitsgehalts mit Blinksignal
- **LowBat / Sabotage / Störung**: Blinksignal mit Farb-Badge direkt im Raumkachel
- **Etagensortierung**: UG → EG → OG → DG → Außenbereich
- **Detailansicht**: Tap auf Raumkachel öffnet Bottom-Sheet mit allen Datenpunkten inkl. Zeitstempel und Profil-Analyse (★ = nicht im Profil)
- **Thermostat-Slider**: Langer Druck auf Solltemperatur öffnet Slider-Dialog; optimistische UI-Aktualisierung ohne Warten auf nächsten Sync
- **Wetterkachel**: Optionale Kachel mit Tagesvorschau, -hoch/tief, Niederschlag

#### Konnektivität & Sync
- **Paralleles Laden**: Alle 4 CGI-Endpunkte gleichzeitig (~75 % schneller)
- **Exponentieller Backoff**: Fehler-Retry mit Verdopplungsintervall (max 16×), Zustand überlebt Rotation
- **Auto-Reload bei Reconnect**: Automatischer Refresh bei Netzwerkrückkehr
- **Connectivity-Check**: WLAN-SSID, Verbindungstyp, letzter Sync-Zeitstempel in der Statusleiste
- **Session-ID (SID)**: Authentifizierter Zugriff auf die CCU via `?sid=`-Parameter
- **Selbstsignierte Zertifikate**: Optionales Trust-All für HTTPS mit eigenem Cert

#### Kamera
- **RTSP-Stream**: ExoPlayer mit automatischem Fallback auf Snapshot bei Verbindungsproblemen
- **MJPEG-Snapshot**: Polling-Fallback mit konfigurierbarem Intervall
- **Mute-Button**: Ton per Tap stummschalten/aktivieren (nur RTSP)

#### DB-Transit-Anzeige
- **Echtzeit-Abfahrtsboard** via `v6.db.transport.rest` oder eigene self-hosted Instanz
- **4 Spalten**: Linie | Zeit | Verspätung/✓ | Umstieg-Info
- **Transfers**: `+X` Umstiege in Spalte 1 (Fußwege nicht mitgezählt)
- **Hin/Rück-Toggle**: Verbindungsrichtung per Tap umkehren
- **Mehrere Verbindungen**: Bis zu 4 konfigurierbare Strecken, per `‹ ›` durchschalten
- **Detailansicht**: Tap auf Verbindungszeile öffnet Bottom-Sheet mit Abfahrt/Ankunft je Etappe
- **Tap auf Header**: Sofortiger manueller Refresh
- **Konfigurierbarer API-Server**: Standard oder eigene Self-Hosting-URL

#### Wetter
- **OpenMeteo-Integration**: Tagesvorschau, Temperatur-Hoch/Tief, Niederschlag, Icon
- **Anzeige-Modi**: Als Überlagerung über Kamera, als eigene Kachel im Rastermenü oder deaktiviert

#### Layout & UI
- **Portrait**: 2 Spalten, DB-Panel + Kamera untereinander
- **Landscape**: 3–4 Spalten im Raster (ganze Breite), DB + Kamera nebeneinander unten
- **Kompakte Toolbar**: 42 dp (Portrait) / 36 dp (Landscape), Launcher-Switch-Button in Menüleiste
- **Farbschema**: Hell, Dunkel oder Systemeinstellung — zur Laufzeit umschaltbar
- **Anpassbare Farben & Schriftgrößen**: Über Einstellungen → Erscheinungsbild
- **Launcher-Modus**: Kann als Standard-Home-App gesetzt werden (Kiosk)
- **Launcher-Switch**: Wechsel zu einem zweiten Launcher per Toolbar-Button

#### Architektur
- **`HmRepository`-Interface**: `RoomAdapter`, `RoomDetailBottomSheet` und `MainActivity` greifen nicht mehr direkt auf den `HomeMatic`-Singleton zu, sondern über das `HmRepository`-Interface — ermöglicht Unit-Tests ohne CCU oder Android-Gerät
- **`FakeHmRepository`**: Fertige Test-Double-Implementierung für Unit-Tests
- **`DeviceProfile`**: Konfigurierbare Gerätezuordnung (Typen, Datenpunktfelder)
- **`AppThemeHelper`**: Zentralisierte Farb- und Schriftgröße-Verwaltung

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

### Session-ID (SID)

Wenn die CCU eine Authentifizierung verlangt:
1. Über die CCU-WebUI oder die REST-API eine Session-ID (SID) erzeugen
2. In den Einstellungen unter **CCU Verbindung → Session-ID** eintragen
3. Die App hängt automatisch `?sid=<wert>` an alle XML-API-Aufrufe an

### DB-Transit einrichten

1. **Einstellungen → DB Transit → Aktivieren**
2. **Von** und **Nach** per Live-Stationssuche setzen (Tipp-Suche, kein OK-Button nötig)
3. Optional: Bis zu 3 weitere Verbindungen unter **Verbindung 2–4** konfigurieren
4. Optional: **Relevante Zwischenstationen** für Umstieg-Anzeige (Spalte 4) hinzufügen
5. Optional: **API-Server** auf eigene `db-rest`-Instanz umstellen (`http://192.168.x.x:3000`)

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
Voraussetzungen: Android Studio Hedgehog+, JDK 17, Android SDK 35

### Konfiguration

Über das **Einstellungs-Symbol** (⚙) in der Toolbar:

| Bereich | Einstellung | Standard |
|---|---|---|
| CCU | Hostname / IP | `homematic-ccu2` |
| CCU | Port | leer (80/443) |
| CCU | HTTPS | Aus |
| CCU | API-Pfad | `/addons/xmlapi/` |
| CCU | Timeout | 5 s |
| CCU | Session-ID (SID) | leer |
| Sync | Intervall | 30 s |
| Sync | Auto-Reload bei Reconnect | An |
| Anzeige | Farbschema | Systemeinstellung |
| Anzeige | Bildschirm aktiv halten | An |
| Kamera | RTSP-URL | leer |
| Kamera | Snapshot-URL | leer |
| Kamera | Fallback-Timeout | 10 s |
| Transit | API-Server | `https://v6.db.transport.rest` |
| Transit | Hin/Rück | Hinfahrt |
| Wetter | Stadt oder Koordinaten | leer |
| Entwickler | Testmodus | Aus |

### Projektstruktur

```
app/src/main/
├── java/com/tvcs/homematic/
│   ├── MainActivity.kt              — Hauptscreen, Coroutines, Timer, Netzwerk
│   ├── HomeMatic.kt                 — CCU-Abruf (parallel), XML-Parsing, Schimmelberechnung
│   ├── HmRepository.kt             — Interface + Adapter + FakeHmRepository (Tests)
│   ├── RoomAdapter.kt               — Raumkacheln via HmRepository (testbar)
│   ├── RoomDetailBottomSheet.kt     — Datenpunkt-Detailansicht via HmRepository
│   ├── DbTransitRepository.kt       — DB-REST API, Retry-Logik, URL-Encoding
│   ├── DbTransitViewController.kt   — Transit-Panel, 4-Spalten-Layout, Hin/Rück
│   ├── TransitDetailBottomSheet.kt  — Verbindungsdetails (Etappen, Zeiten)
│   ├── CameraViewController.kt      — RTSP/Snapshot, Mute, Fallback
│   ├── WeatherViewController.kt     — OpenMeteo-Integration, Kachel/Overlay
│   ├── SettingsActivity.kt          — Einstellungen (PreferenceFragmentCompat)
│   ├── NetworkUtils.kt              — Netzwerkstatus, WLAN-Info, CCU-Ping
│   ├── AppThemeHelper.kt            — Farben, Schriftgrößen, Theme
│   ├── PreferenceKeys.kt            — Alle Preference-Keys als Konstanten
│   └── ...
├── java/com/homematic/
│   └── Models.kt                    — Kotlin-Datenklassen (Room, Device, Channel…)
├── assets/                          — Test-XML-Dateien
└── res/
    ├── layout/                      — Portrait-Layouts
    ├── layout-land/                 — Landscape-Layouts (3–4 Spalten, DB+Cam nebeneinander)
    ├── values/                      — Deutsche Strings (Standard)
    └── values-en/                   — Englische Strings (automatisch auf EN-Geräten)
```

### Technologie

| | |
|---|---|
| Sprache | Kotlin (JVM 17) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |
| Build | Gradle 8.4 + AGP 8.2 |
| Async | Kotlin Coroutines |
| XML | Simple XML Framework 2.7.1 |
| Video | ExoPlayer / Media3 |
| UI | Material3 DayNight |
| Transit | db-rest / db-vendo-client REST API |
| Wetter | Open-Meteo (kostenlos, kein API-Key) |

---

<a name="english"></a>

## English

An Android launcher for the **HomeMatic CCU** (CCU2/CCU3/RaspberryMatic) displaying room and device status directly on the home screen — optimised for wall tablets and kiosk displays.

### Features

#### Room Overview
- **Real-time grid** of all HomeMatic rooms with temperature, humidity and window status
- **Color-coded window indicators**: Green = closed · Gold = tilted · Red = open
- **Mould warning**: Automatic absolute humidity calculation with blinking alert
- **LowBat / Sabotage / Fault**: Blinking badge directly in the room tile
- **Floor sorting**: Basement → Ground → Upper → Attic → Outdoor
- **Detail view**: Tap a room tile to open a bottom sheet with all data points, timestamps and profile analysis (★ = not in profile)
- **Thermostat slider**: Long-press the set-temperature row for a slider dialog; optimistic UI update without waiting for the next sync
- **Weather tile**: Optional tile showing today's forecast, high/low, precipitation

#### Connectivity & Sync
- **Parallel loading**: All 4 CGI endpoints fetched simultaneously (~75% faster)
- **Exponential backoff**: Error retry with doubling interval (max 16×), state survives rotation
- **Auto-reload on reconnect**: Automatic refresh when network returns
- **Connectivity check**: Wi-Fi SSID, connection type, last sync timestamp in the status bar
- **Session ID (SID)**: Authenticated CCU access via `?sid=` query parameter
- **Self-signed certificates**: Optional trust-all for HTTPS with custom cert

#### Camera
- **RTSP stream**: ExoPlayer with automatic fallback to snapshot on connection failure
- **MJPEG snapshot**: Polling fallback with configurable interval
- **Mute button**: Silence/restore audio with a tap (RTSP only)

#### DB Transit Display
- **Real-time departure board** via `v6.db.transport.rest` or a self-hosted instance
- **4 columns**: Line | Time | Delay/✓ | Transfer info
- **Transfers**: `+X` count in column 1 (walking legs excluded)
- **Outward/Return toggle**: Flip direction with a tap
- **Multiple connections**: Up to 4 configurable routes, switchable via `‹ ›`
- **Detail view**: Tap a departure row for a bottom sheet with per-leg departure and arrival times
- **Tap header**: Immediate manual refresh
- **Configurable API server**: Default public endpoint or custom self-hosted URL

#### Weather
- **Open-Meteo integration**: Daily forecast, high/low temperature, precipitation, icon
- **Display modes**: Overlay on camera, tile in the room grid, or disabled

#### Layout & UI
- **Portrait**: 2 columns, DB panel + camera stacked vertically
- **Landscape**: 3–4 columns (full width), DB + camera side by side at the bottom
- **Compact toolbar**: 42 dp (portrait) / 36 dp (landscape), launcher-switch button in menu bar
- **Color scheme**: Light, Dark, or System — switchable at runtime
- **Customisable colors & font sizes**: via Settings → Appearance
- **Launcher mode**: Can be set as the default home app (kiosk)
- **Launcher switch**: Switch to a secondary launcher via toolbar button

#### Architecture
- **`HmRepository` interface**: `RoomAdapter`, `RoomDetailBottomSheet` and `MainActivity` no longer access the `HomeMatic` singleton directly — dependency injection via `HmRepository` enables unit testing without a real CCU or Android device
- **`FakeHmRepository`**: Ready-to-use test double for unit tests
- **`DeviceProfile`**: Configurable device-type-to-datapoint mapping
- **`AppThemeHelper`**: Centralised color and font-size management

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

### Session ID (SID)

If your CCU requires authentication:
1. Generate a session ID via the CCU web UI or REST API
2. Enter it in **Settings → CCU Connection → Session ID**
3. The app automatically appends `?sid=<value>` to all XML-API calls

### DB Transit Setup

1. **Settings → DB Transit → Enable**
2. Set **From** and **To** via live station search (type-as-you-search, no OK button needed)
3. Optionally configure up to 3 more routes under **Connection 2–4**
4. Optionally add **Relevant intermediate stops** for the transfer column (column 4)
5. Optionally point **API server** to a self-hosted `db-rest` instance (`http://192.168.x.x:3000`)

### Installation

**Direct APK:**
1. Download the release APK
2. Enable **Settings → Security → Unknown sources**
3. Install and set as default launcher (recommended for tablet kiosk use)

**From source:**
```bash
git clone <repository-url>
cd HomeMaticLauncher
./gradlew assembleRelease
```
Requirements: Android Studio Hedgehog+, JDK 17, Android SDK 35

### Configuration

Via the **settings icon** (⚙) in the toolbar:

| Section | Setting | Default |
|---|---|---|
| CCU | Hostname / IP | `homematic-ccu2` |
| CCU | Port | empty (80/443) |
| CCU | HTTPS | Off |
| CCU | API path | `/addons/xmlapi/` |
| CCU | Timeout | 5 s |
| CCU | Session ID (SID) | empty |
| Sync | Interval | 30 s |
| Sync | Auto-reload on reconnect | On |
| Display | Color scheme | System default |
| Display | Keep screen on | On |
| Camera | RTSP URL | empty |
| Camera | Snapshot URL | empty |
| Camera | Fallback timeout | 10 s |
| Transit | API server | `https://v6.db.transport.rest` |
| Transit | Direction | Outward |
| Weather | City or coordinates | empty |
| Developer | Test mode | Off |

### Architecture

```
app/src/main/
├── java/com/tvcs/homematic/
│   ├── MainActivity.kt              — Main screen, coroutines, timer, network callbacks
│   ├── HomeMatic.kt                 — CCU fetch (parallel), XML parsing, mould calculation
│   ├── HmRepository.kt             — Interface + adapter + FakeHmRepository (tests)
│   ├── RoomAdapter.kt               — Room tiles via HmRepository (unit-testable)
│   ├── RoomDetailBottomSheet.kt     — Data-point detail view via HmRepository
│   ├── DbTransitRepository.kt       — DB REST API, retry logic, URL encoding
│   ├── DbTransitViewController.kt   — Transit panel, 4-column layout, direction toggle
│   ├── TransitDetailBottomSheet.kt  — Journey details (legs, times)
│   ├── CameraViewController.kt      — RTSP/snapshot, mute, fallback
│   ├── WeatherViewController.kt     — Open-Meteo integration, tile/overlay
│   ├── SettingsActivity.kt          — Settings (PreferenceFragmentCompat)
│   ├── NetworkUtils.kt              — Network status, Wi-Fi info, CCU ping
│   ├── AppThemeHelper.kt            — Colors, font sizes, theme
│   ├── PreferenceKeys.kt            — All preference keys as constants
│   └── ...
├── java/com/homematic/
│   └── Models.kt                    — Kotlin data classes (Room, Device, Channel…)
├── assets/                          — Test XML files
└── res/
    ├── layout/                      — Portrait layouts
    ├── layout-land/                 — Landscape layouts (3–4 cols, DB+cam side by side)
    ├── values/                      — German strings (default)
    └── values-en/                   — English strings (auto-selected on EN devices)
```

### Tech Stack

| | |
|---|---|
| Language | Kotlin (JVM 17) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |
| Build | Gradle 8.4 + AGP 8.2 |
| Async | Kotlin Coroutines |
| XML | Simple XML Framework 2.7.1 |
| Video | ExoPlayer / Media3 |
| UI | Material3 DayNight |
| Transit | db-rest / db-vendo-client REST API |
| Weather | Open-Meteo (free, no API key required) |

### Known Limitations

- **Screen off** requires Device Admin rights (not yet implemented; the app shows a hint instead)
- **HTTPS with self-signed certificate**: Enable the trust-self-signed option in settings — the cert does not need to be in the system trust store
- The public `v6.db.transport.rest` instance has a low rate limit and may return 503 under load; self-hosting `db-rest` via Docker is recommended for reliable operation

---

### Changelog

#### v2.1.0 (current)
- **`HmRepository` interface** — `RoomAdapter`, `RoomDetailBottomSheet` and `MainActivity` fully decoupled from `HomeMatic` singleton; `FakeHmRepository` test double included
- **DB Transit** — full departure board with 4 aligned columns, Hin/Rück toggle, up to 4 connections, live station search (type-as-you-search, no second dialog), self-hosted API server option, Retry + exponential backoff, `%20` URL encoding fix
- **Transit detail sheet** — per-leg origin/destination with planned + realtime times and delay badges
- **Transit header tap** — manual refresh on tap
- **Camera mute button** — inline ImageButton, visible only during RTSP playback, state survives reconnects
- **Launcher switch in toolbar** — FABs removed; launcher-switch icon added to menu bar
- **Landscape layout** — room grid fills full width (3–4 columns), DB + camera side by side below
- **Compact toolbar** — 42 dp portrait / 36 dp landscape; thinner status strip
- **Weather tile** — proper `getItemViewType()` instead of position-0 hack; rooms keep their original positions
- **Optimistic thermostat update** — tile reflects new setpoint immediately via `optimisticSetTemp()`
- **Backoff state survives rotation** — `backoffMultiplier` persisted in `onSaveInstanceState`
- **`switchSummary` deduplicated** — shared top-level `sharedSwitchSummary()` function
- **`R.xml.preferences_main` fix** — corrected resource reference in `SettingsFragment`
- **`minimumWidth` fix** — `LinearLayout.minimumWidth` instead of non-existent `minWidth`
- **Missing transit pref keys** — `TRANSIT_BASE_URL`, `TRANSIT_EXTRA_CONNECTIONS`, `TRANSIT_REFRESH_INTERVAL` added to change listener
- **Weather panel wired** — `WeatherViewController` now receives the real `camera_panel` view
- **Status text** — `maxLines=1` + `ellipsize=end` prevents clipping on long error messages
- **SID support, parallel CCU fetching, gear FAB → settings icon, Light/Dark/System theme, bilingual README
- **Timer reset fix, `PreferenceKeys.kt`, `getCcuBaseUrl()` refactor, sync timestamp
- **Multiple concurrency and API deprecation fixes, SP text sizes, all strings in `strings.xml`
- **And more

#### v2.0.0
- Full Java → Kotlin, AsyncTask → Coroutines, min SDK 26, Material3, status bar

#### v1.0
- Java, AsyncTask, basic room/temperature/window display
