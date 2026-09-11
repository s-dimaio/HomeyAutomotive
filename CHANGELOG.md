# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
