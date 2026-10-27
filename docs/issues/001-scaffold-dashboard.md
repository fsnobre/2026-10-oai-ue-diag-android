# Issue 001 — Scaffold Android app and implement live telephony dashboard

## Goal

Create the first working version of **OAI UE Diag**, a normal non-root Android app for collecting public Android telephony state during OAI 5G SA/COTS UE experiments.

The app must compile, install on a real phone via ADB, request the needed permissions, and show live device/SIM/network/cell information.

## Context

This app is for OpenAirInterface 5G RAN/Core lab diagnosis. It must help compare COTS Android phones such as POCO X4 GT and Samsung S25 FE against an OAI gNB/Core setup.

A normal Android app cannot directly read NAS/RRC reject causes, force NR-only, select PLMN, edit APN, or control IMS. The app should only use public Android APIs and clearly present what Android exposes.

## Requirements

### Project setup

- Kotlin
- Jetpack Compose
- Material 3
- Gradle Kotlin DSL
- Package: `br.dev.nobre.oaiuediag`
- Minimum SDK: 29
- Target SDK: latest stable installed
- Single-activity app

### Permissions

Request runtime permissions for:

- `READ_PHONE_STATE`
- `ACCESS_FINE_LOCATION`

Declare only reasonable permissions:

- `READ_PHONE_STATE`
- `READ_BASIC_PHONE_STATE` if available/appropriate
- `ACCESS_FINE_LOCATION`
- `ACCESS_NETWORK_STATE`
- `INTERNET`

### Dashboard

Implement cards for:

1. Device information
   - Manufacturer
   - Model
   - Android version
   - SDK version
   - Build fingerprint

2. SIM/subscription information
   - Active subscriptions
   - SIM slot
   - Carrier name if available
   - MCC/MNC if available
   - SIM state

3. Network/service information
   - Voice network type
   - Data network type
   - Roaming
   - Service state if available
   - Data state if available

4. Cell information
   - List registered cell first
   - Show NR cells when available
   - Show LTE cells when available

### NR cell parsing

For `CellInfoNr`, parse and display when available:

- Registered flag
- MCC
- MNC
- NCI
- PCI
- TAC
- NRARFCN
- Bands if available
- SS-RSRP
- SS-RSRQ
- SS-SINR

### LTE cell parsing

For LTE cells, parse basic fields when available:

- Registered flag
- MCC/MNC
- CI
- PCI
- TAC
- EARFCN
- RSRP
- RSRQ
- RSSI if available

### Tools

Add buttons:

- Refresh now
- Open Android Testing / Phone Info screen
- Open mobile network settings as fallback

The Phone Info button may use common hidden intents/dialer tricks, but must handle failure gracefully and explain that some OEMs block direct access.

### Code organization

Use this structure or a clean equivalent:

- `MainActivity.kt`
- `data/TelemetrySnapshot.kt`
- `data/CellModels.kt`
- `telephony/TelephonyReader.kt`
- `ui/DashboardScreen.kt`
- `ui/components/*.kt`

### README

Add `README.md` with:

- Project purpose
- Known limitations
- Build command
- Install command
- Permission explanation

Example commands:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
