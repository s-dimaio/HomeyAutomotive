# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.10.0] - 2026-10-30

### Added
- **Scoped User Session Token Authentication**: Fully upgraded OAuth2 token exchange via Athom Bound Delegation tokens. Replaced legacy owner tokens with user-scoped credentials mapped directly to the local Homey Pro user gateway.
- **Smart Re-Authentication Detection & In-Car Notice**: Added graceful detection for HTTP 401 re-authentication states (`REAUTH_REQUIRED` / `COMPANION_UPDATED`). The app displays an informative dedicated in-car template explaining the update and offering a 1-tap re-login action.
- **Auto-Prefilled Homey ID on Re-Authentication**: The input field in `HomeyIdSetupScreen` is now pre-filled with the active Homey hub ID, allowing the driver to confirm with a single tap without manual typing.
- **Direct Native Flow Execution**: Standard Flows and Advanced Flows are now triggered directly via native Homey Pro REST API endpoints (`POST /api/manager/flow/flow/{id}/trigger` and `POST /api/manager/flow/advancedflow/{id}/trigger`), completely eliminating the prerequisite for the HomeyScript companion proxy.

### Changed
- **Zero Prerequisite for Flows**: Users no longer need to install or configure HomeyScript on Homey Pro to execute flows from the vehicle.
- **Optimized Re-auth Navigation**: Added back navigation support during re-authentication stacks, preventing drivers from getting stuck on input screens.

## [1.9.2] - 2026-10-17

### Added
- **Cryptographic AAOS Host Validation (Phase 6 Hardening)**: Configured dual-mode host validation in `HomeyCarAppService.createHostValidator()`. Production release builds strictly enforce official Google Automotive Services (GAS) and OEM platform host certificate allowlists (`androidx.car.app.R.array.hosts_allowlist_sample`), safeguarding against host hijacking, while debug builds permit development hosts.
- **Automated Network Header Redaction (Phase 7 Hardening)**: Enabled `.redactHeader("Authorization")` and `.redactHeader("x-homey-id")` on all OkHttp logging interceptors, preventing bearer authentication tokens and cloud identifiers from leaking in Logcat output.
- **Sanitized HTTP Logger & PII Redaction (Phase 7 Hardening)**: Implemented `SanitizedHttpLogger` with Android ICU-compliant regex capturing groups, securely redacting 24-character hexadecimal Athom IDs (`677c***6f27`), 64-character session tokens in URLs (`?session=3ac05f06...***`), email addresses, and GPS coordinates across network traffic and repository logs.
- **System Backup Protection (Phase 7 Hardening)**: Enforced `android:allowBackup="false"` in `AndroidManifest.xml` to prevent unauthorized extraction of encrypted credentials via ADB backups on vehicle head units.
- **Interactive Demo Proximity Alert**: Added simulated proximity alert trigger in Demo Mode Settings, allowing instant testing of arrival notification banners with the mock gate barrier.

## [1.9.1] - 2026-10-14

### Added
- **Multi-Hub Geofencing & Hub Context**: Extended geofencing and proximity notifications with multi-hub awareness, dynamically citing the active hub name ("Near Homey...", "Departure from Homey...").
- **Dynamic Climate & Thermometers**: Integrated multi-thermometer capability aggregation (`measure_temperature`), computing real-time indoor average and outdoor readings dynamically within the Home Overview section header.

## [1.9.0] - 2026-10-08

### Added
- **Granular Home Location & Proximity Controls**: Introduced dedicated Home Location toggle in Settings:
  - Supports cycling through proximity radiuses (100m, 200m, 300m, or Alerts Disabled).
  - Activating Home Location synchronizes coordinates from Homey Pro and registers the geofence perimeter with Google Play Services.
  - Deactivating removes the geofence perimeter and clears stored coordinates.
- **Bidirectional Geofence Flow Triggers**: Added `postGeofenceEvent` (`POST /geofence/event`) communicating vehicle arrivals and departures to the companion app to fire Homey Pro Flow trigger cards (`geofence_enter`, `geofence_exit`).
- **Demo Mode Mock Barriers**: Added mock "Driveway Gate" device and expanded barrier test suite in Demo Mode.

## [1.8.0] - 2026-09-21

### Added
- **Vector Graphics & Crisp Badges**: Replaced raster assets with official, high-resolution vector drawables for device state badges (Power on/off, Locked, Unlocked) and flow types (Standard Flow, Advanced Flow).
- **Startup Connection Feedback**: Introduced a brief driver toast notification upon app launch confirming the connected Homey hub.
- **Driving Restriction Notices**: Added clear in-motion summary notices informing drivers when extended lists are temporarily truncated for safety while driving.

### Changed
- **Driver Safety & Anti-Lockout (AAOS In-Place Refresh)**: Replaced terminal error and loading templates with in-place template refreshes (`GridTemplate` and `ListTemplate`), strictly avoiding step-limit exhaustion and preventing driver distraction lockouts while in motion.
- **Optimized Startup Flow**: Eliminated redundant ghost screen pushes during startup, launching directly into `MainTabScreen` when an active hub is already paired.
- **Enhanced Badge Resizing & Visual Balance**: Fine-tuned device tile badge scaling for optimal readability and contrast on automotive center displays.

## [1.7.0] - 2026-09-18

### Added
- **Manual Flow Execution**: Full support for triggering both Standard and Advanced Homey Flows directly from the vehicle via the companion app's HomeyScript execution proxy.
- **Dedicated Scenarios Tab**: Active Scenarios tab organizing all triggerable flows by folder with transient execution states and driver-safe toast confirmations.
- **Smart Error Handling**: Dedicated in-car error messages if required companion components (HomeyScript) are missing on Homey Pro.

### Changed
- **Seamless Session Renewal**: Fully automatic background session refresh preventing expired session disruptions.
- **Connection Reliability**: Optimized token handling ensuring instant reconnects without manual login.

## [1.6.0] - 2026-09-13

### Added
- **Unified Devices Tab**: Introduced a unified "Devices" tab grouping all lights, doors, locks, and barriers by room in a single hierarchical view.
- **Dynamic Home Overview**: Added a prominent "Home Overview" card at the top of the Devices tab with real-time dynamic status tint (green when all access points are secure and lights are off; amber when open or active) and aggregated counters.
- **Contextual Room Metrics**: Integrated live room metrics into section headers:
  - Average room temperature computed across all sensors and thermostats reporting `measure_temperature`.
  - Active lights count displayed only when at least one light is currently on.
  - Open barriers count displayed only when at least one access point is currently open.
- **Active Room Indicator**: Added the official Homey blue dot indicator (`#0082FA`) to room headers when occupancy or activity is detected in the zone.
- **Safe Unicode Bezel Inset**: Applied typographic En-Space (`\u2002`) indentation to all section headers to prevent Car App host bezel clipping and ensure perfect visual alignment with device rows.
- **Official Homey Vector Drawables**: Integrated official Homey vector icons for Devices (`ic_tab_devices.xml`), Flows (`ic_tab_flows.xml`), and Active Room Indicator (`ic_zone_active_dot.xml`).
- **Flows Tab Preparation**: Added the dedicated "Flows" tab with an informative placeholder, establishing the 4-tab foundation for upcoming manual flow execution (Phase 4).

### Changed
- **4-Tab Navigation Hierarchy**: Reorganized main navigation to align with the official Homey ecosystem: Home (`tab_home`), Devices (`tab_devices`), Flows (`tab_flows`), and Settings (`tab_settings`).
- **Anti-Clutter Header Design**: Neutral/negative states ("All lights off", "All barriers secure") are cleanly omitted at the individual room level to reduce driver visual distraction while preserving complete summaries at the Home Overview level.
- **Enriched Demo Mode**: Updated mock data provider with room activity states, realistic temperature simulations (`21.5°C` Ground Floor, `18.0°C` Outside), and interactive toggling for Google Play reviewers.

## [1.5.5] - 2026-09-12

### Changed
- **Location Prominent Disclosure & Transparency**: Refined in-app location disclosure (`PermissionScreen`) with plain-language usage explanations, removing technical jargon, explicitly citing the registered app name (`Homey for Android Automotive`), and strongly emphasizing the optional nature of the permission while clarifying that all other Homey features remain fully operational without disruption.

## [1.5.4] - 2026-09-11

### Added
- **In-App Privacy Policy Link**: Added direct access to the Privacy Policy URL from the in-car `SystemInfoScreen`.

### Changed
- **Google Play Compliance & Privacy**: Comprehensively updated `privacy.html` with explicit disclosures for background location access (`ACCESS_BACKGROUND_LOCATION`), local geofence evaluation, and Homey Companion App event integration.
- **Build Performance Optimization**: Drastically improved Android Studio build speed via `gradle.properties` (4 GB JVM heap, Configuration Cache, Build Cache, and parallel task execution).

## [1.5.3] - 2026-09-10

### Changed
- **Location Prominent Disclosure**: Implemented the compliant 2-step in-app disclosure (`PermissionScreen`) strictly adhering to Google Play policy wording and affirmative consent buttons ("Agree & Continue" / "No thanks").

## [1.5.2] - 2026-09-08

### Fixed
- **Release Stability & ProGuard**: Resolved release crash caused by R8 obfuscation on DTO and Geofence models; hardened keep rules in `proguard-rules.pro`.

## [1.5.1] - 2026-09-06

### Added
- **Demo Mode for Google Play Review**: Introduced interactive in-app demo mode triggered by entering `demo` as Homey ID, bypassing QR authentication with realistic mock devices.

## [1.5.0] - 2026-09-03

### Added
- **Smart Geofencing & In-Car Alerts**: Introduced proactive background assistance with home geofence detection: departure open door warnings, optional auto-close routines, and arrival gate/garage shortcuts.

## [1.4.20] - 2026-07-27

### Changed
- **System Update**: Added support for Android 16 (API 36) to ensure compatibility with Google Play Store requirements.
- **Technical Updates**: Updated Android Gradle Plugin to version 9.3.1.
- **Improvements**: Internal optimizations and version increment.
