# HomeMatic Launcher

> **Deutsch** | [English below](#english)

---

## Deutsch

Ein Android-Launcher für die **HomeMatic CCU** (CCU2/CCU3/RaspberryMatic), der Raum- und Gerätestatus direkt auf dem Homescreen anzeigt.

### Funktionen

- **Echtzeit-Übersicht** aller HomeMatic-Räume mit Temperatur, Luftfeuchtigkeit und Fensterstatus
- **Farbige Fensterindikatoren**: Grün = geschlossen · Gold = gekippt · Rot = offen
- **Schimmelwarnung**: Automatische Berechnung des absoluten Feuchtigkeitsgehalts mit Blinksignal
- **LowBat-Warnung**: Blinksignal bei schwacher Batterie
- **Etagensortierung**: UG → EG → OG → DG → Außenbereich
- **Netzwerkstatus-Anzeige**: WLAN-SSID, Verbindungstyp, letzter Sync-Zeitstempel
- **Connectivity-Check**: Auto-Reload bei Netzwerkwiederkehr
- **Session-ID (SID)**: Authentifizierter Zugriff auf die CCU via `sid=`-Parameter
- **Paralleles Laden**: Alle 4 CGI-Endpunkte werden gleichzeitig abgerufen (~75% schneller)
- **Farbschema**: Hell, Dunkel oder Systemeinstellung — zur Laufzeit umschaltbar
- **Einstellungs-Button**: Kleiner Zahnrad-FAB für schnellen Zugriff auf die Konfiguration
- **Testmodus**: Lokale XML-Dateien statt CCU-Verbindung
- **Vollbild-Modus**: Als Launcher (Home-App) nutzbar

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

Über den **Zahnrad-Button** (unten rechts) oder das Einstellungs-Symbol in der Toolbar:

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
| Anzeige | Statusleiste | Aus |
| Entwickler | Testmodus | Aus |
| Entwickler | Außenraum-Name | `Aussen` |

### Projektstruktur

```
app/src/main/
├── java/com/tvcs/homematic/
│   ├── MainActivity.kt       — Hauptscreen, Coroutines, Timer, Netzwerk
│   ├── HomeMatic.kt          — CCU-Abruf (parallel), XML-Parsing, Schimmelberechnung
│   ├── RoomAdapter.kt        — Raumkacheln-Adapter
│   ├── SettingsActivity.kt   — Einstellungen (PreferenceFragmentCompat)
│   ├── NetworkUtils.kt       — Netzwerkstatus, WLAN-Info, CCU-Ping
│   └── PreferenceKeys.kt     — Alle Preference-Keys als Konstanten
├── java/com/homematic/
│   └── Models.kt             — Kotlin-Datenklassen (Room, Device, Channel…)
├── assets/                   — Test-XML-Dateien
└── res/
    ├── values/               — Deutsche Strings (Standard)
    └── values-en/            — Englische Strings (automatisch auf EN-Geräten)
```

### Technologie

| | |
|---|---|
| Sprache | Kotlin (JVM 17) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |
| Build | Gradle 8.4 + AGP 8.2 |
| Async | Kotlin Coroutines (paralleles Laden) |
| XML | Simple XML Framework 2.7.1 |
| Theme | Material3 DayNight |

---

<a name="english"></a>

## English

An Android launcher for the **HomeMatic CCU** (CCU2/CCU3/RaspberryMatic) that displays room and device status directly on the home screen.

### Features

- **Real-time overview** of all HomeMatic rooms with temperature, humidity and window status
- **Color-coded window indicators**: Green = closed · Gold = tilted · Red = open
- **Mould warning**: Automatic absolute humidity calculation with blinking alert
- **LowBat warning**: Blinking indicator for low battery devices
- **Floor sorting**: Basement → Ground → Upper → Attic → Outdoor
- **Network status bar**: Wi-Fi SSID, connection type, last sync timestamp
- **Connectivity check**: Auto-reload when network returns
- **Session ID (SID)**: Authenticated CCU access via `sid=` query parameter
- **Parallel loading**: All 4 CGI endpoints fetched simultaneously (~75% faster)
- **Color scheme**: Light, Dark, or System — switchable at runtime
- **Settings button**: Small gear FAB for quick access to configuration
- **Test mode**: Local XML assets instead of a live CCU
- **Full-screen mode**: Usable as a home launcher (kiosk)

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

Via the **gear button** (bottom right) or the settings icon in the toolbar:

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
| Display | Status bar | Off |
| Developer | Test mode | Off |
| Developer | Outdoor room name | `Aussen` |

### Architecture

```
app/src/main/
├── java/com/tvcs/homematic/
│   ├── MainActivity.kt       — Main screen, coroutines, timer, network callbacks
│   ├── HomeMatic.kt          — CCU fetch (parallel), XML parsing, mould calculation
│   ├── RoomAdapter.kt        — Room tile adapter
│   ├── SettingsActivity.kt   — Settings (PreferenceFragmentCompat)
│   ├── NetworkUtils.kt       — Network status, Wi-Fi info, CCU ping
│   └── PreferenceKeys.kt     — All preference keys as constants
├── java/com/homematic/
│   └── Models.kt             — Kotlin data classes (Room, Device, Channel…)
├── assets/                   — Test XML files
└── res/
    ├── values/               — German strings (default)
    └── values-en/            — English strings (auto-selected on EN devices)
```

### Tech Stack

| | |
|---|---|
| Language | Kotlin (JVM 17) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 35 (Android 15) |
| Build | Gradle 8.4 + AGP 8.2 |
| Async | Kotlin Coroutines (parallel loading) |
| XML | Simple XML Framework 2.7.1 |
| Theme | Material3 DayNight |

### Known Limitations

- **Screen off** requires Device Admin rights (not yet implemented; app shows a hint instead)
- The `StaggeredGridView` is retained from the original; replacing it with `RecyclerView` + `StaggeredGridLayoutManager` is planned
- **HTTPS with self-signed certificate**: The cert must be manually trusted in the Android trust store

---

### Changelog

#### v2.3.0 (current)
- **SID support**: Session-ID preference, `?sid=` appended to all XML-API URLs
- **Parallel CCU fetching**: 4 endpoints fetched simultaneously via `async/await` (~75% faster)
- **Parallel XML parsing**: 4 documents deserialized in parallel inside `processData`
- **`StringReader` in `deserialize`**: avoids intermediate byte-array copies
- **Pre-sized HashMaps**: capacity set at construction from known element count
- **Gear FAB**: replaces the doorbell FAB; opens Settings directly
- **Light / Dark / System theme**: `AppCompatDelegate.setDefaultNightMode`, switchable at runtime
- **`SettingsTheme`**: separate Material3 theme for Settings screen (no black background)
- **`androidx.core.app.PARENT_ACTIVITY`** meta-data replaces old support library name
- **`configChanges navigation`** removed from Manifest
- **Bilingual README** (DE/EN)
- **`values-en/strings.xml`**: full English string set; Android selects automatically

#### v2.2.0
- Timer reset on Settings return fixed
- `runOnUiThread` in broadcast receiver removed
- `withContext(Dispatchers.Main)` in fragment fixed
- `PreferenceKeys.kt` — all 13 keys as constants
- `getCcuBaseUrl()` made private; `getCcuHost()` / `isCcuHttps()` as public helpers
- `stateDeviceSet` as `val` instead of `get()`-property
- `SimpleDateFormat` → `DateTimeFormatter`
- Sync timestamp in status bar
- `isCcuReachable()` respects HTTPS (port 443)
- `show_status_bar` change shows `AlertDialog`

#### v2.1.0
- `SharedPreferenceChangeListener` GC bug fixed
- `continue` in `when` Kotlin bug fixed
- Concurrent load guard
- Toast spam on display timeout fixed
- `isCcuReachable` IO dispatcher fixed
- `WifiManager.connectionInfo` deprecated API fixed
- `FLAG_FULLSCREEN` deprecated API fixed
- `PorterDuff.Mode` deprecated API fixed
- Activity context leak in `HomeMatic` fixed
- Atomic state assignment in `processData`
- `viewMap` global adapter bug fixed (cross-item ID collision)
- Pixel text sizes → SP
- All preference strings moved to `strings.xml`
- Unused permissions removed

#### v2.0.0
- Full Java → Kotlin conversion
- `AsyncTask` → Kotlin Coroutines
- Min SDK raised to API 26
- `PreferenceFragment` → `PreferenceFragmentCompat`
- `ConnectivityManager.NetworkCallback` for live connectivity changes
- Status bar with network info and error display
- New settings: HTTPS, port, API path, timeout, auto-reload, keep-screen-on
- CCU connection test from settings
- ProGuard rules for release builds

#### v1.0 (Initial)
- Java, AsyncTask, minSdk 22
- Basic room/temperature/window display, mould warning
