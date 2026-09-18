# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
