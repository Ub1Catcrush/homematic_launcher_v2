# Project Context: HomeMaticLauncher

## Overview
HomeMaticLauncher is a specialized Android launcher designed for wall-mounted tablets and kiosk displays. It serves as a dashboard for HomeMatic CCU (CCU2/CCU3/RaspberryMatic) systems, integrating various smart home and information services into a single interface.

## Tech Stack
- **Language:** Kotlin (JVM 17)
- **Minimum SDK:** 26 (Android 8.0)
- **Target SDK:** 36 (Android 16)
- **UI Framework:** Material3 with DayNight support
- **Asynchronous Programming:** Kotlin Coroutines and StateFlow
- **Video/Camera:** Media3/ExoPlayer for RTSP/MJPEG, CameraX for local motion detection
- **Networking:** OkHttp (WebSocket for Home Assistant), REST APIs for Weather (Open-Meteo) and Transit (db-rest)
- **Build System:** Gradle with Version Catalogs

## Key Architecture & Components

### Core Logic
- `MainActivity.kt`: The central hub that manages the lifecycle, network changes, and orchestrates various services.
- `HomeMatic.kt`: Handles fetching and parsing data from the HomeMatic CCU via the XML-API.
- `HmRepository.kt`: Interface for HomeMatic data, allowing for fake implementations in tests.

### Integrations
- `HaRepository.kt` & `HaTileViewController.kt`: Manages the WebSocket connection and UI for multiple Home Assistant tiles.
- `CameraViewController.kt`: Handles RTSP streams and MJPEG snapshots with automatic fallback and scaling logic.
- `DbTransitViewController.kt`: Displays real-time public transit departures.
- `WeatherViewController.kt`: Integrates Open-Meteo for local weather forecasts.

### Motion Detection & Power Management
- `MotionDetectionService.kt`: A Foreground Service that keeps motion detection running even when the screen is off.
- `MotionDetectionEngine.kt`: Contains the pixel-difference algorithm and Region of Interest (ROI) logic.
- `LocalCameraMotionSource.kt`: Implements motion detection using the device's own camera via CameraX.
- `ScreenWakeController.kt`: Manages `WakeLock` and window flags to wake the device and control screen timeouts.

### UI & Configuration
- `RoomAdapter.kt`: The main grid adapter managing room tiles and special integration tiles (HA, Transit, Weather).
- `SettingsActivity.kt`: Categorized settings using standard Android Preferences with custom components like `RoiPickerPreference`.
- `ProfileExportImport.kt`: Logic for JSON-based configuration backup and restore.

## Project Structure
- `app/src/main/java/com/tvcs/homematic/`: Contains all Kotlin source code.
- `app/src/main/res/xml/`: Defines the hierarchical settings structure.
- `app/src/main/res/layout/`: XML layouts for activities, fragments, and custom view components.

## Developer Notes
- The app is designed to be highly resilient against network failures with backoff strategies and guarded UI updates.
- Performance is a priority for the motion detection engine to ensure it can run on lower-end tablet hardware.
- It supports both Portrait and Landscape orientations with adaptive grid layouts.
