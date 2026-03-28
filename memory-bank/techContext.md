# Tech Context — Tail

**Last updated:** 2026-03-28T15:47Z

## Technology Stack

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Kotlin | 2.0.21 |
| UI Framework | Jetpack Compose (Material3) | BOM 2024.09.00 |
| Build System | Gradle (Kotlin DSL) | AGP 8.13.2 |
| Architecture | MVVM | — |
| Navigation | Navigation Compose | 2.7.7 |
| Settings Storage | DataStore Preferences | 1.1.1 |
| JSON Parsing | Gson | 2.10.1 |
| File Access | DocumentFile (SAF) | 1.0.1 |
| ViewModel | Lifecycle ViewModel Compose | 2.8.0 |
| Async | Kotlinx Coroutines Android | 1.7.3 |
| Icons | Material Icons Extended | (via BOM) |
| Min SDK | 26 (Android 8.0) | — |
| Target SDK | 36 | — |
| Compile SDK | 36 | — |
| JVM Target | 11 | — |

## Project Configuration

- **Package:** `com.example.tail`
- **Application ID:** `com.example.tail`
- **Namespace:** `com.example.tail`
- **Compose:** Enabled via `kotlin-compose` plugin
- **No Room/SQLite** — All data stored in JSON files accessed via SAF

## Build & Run

```bash
# Build debug APK
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

## Key Dependencies (from libs.versions.toml)

- `androidx-core-ktx` 1.10.1
- `androidx-lifecycle-runtime-ktx` 2.8.0
- `androidx-lifecycle-viewmodel-compose` 2.8.0
- `androidx-activity-compose` 1.12.4
- `androidx-compose-bom` 2024.09.00
- `androidx-navigation-compose` 2.7.7
- `androidx-datastore-preferences` 1.1.1
- `androidx-documentfile` 1.0.1
- `gson` 2.10.1
- `kotlinx-coroutines-android` 1.7.3

## External Data Files

| File | Purpose | Format |
|------|---------|--------|
| `habitsdb_phone.txt` | Primary phone habit database | JSON: `{ "Habit": { "date": count } }` |
| `habitsdb.txt` | Unified habit database (shared with PC) | Same JSON format |
| `habitsdb_without_phone_totals.txt` | Historical data (optional) | Same JSON format |
| `screens_layout.json` | Screen/page layout configuration | JSON array of screen objects |
| Per-habit text log files | Text entries for text-input habits | JSON: `{ "timestamp": "text" }` |
| Dated entry files | Dream journal / dated entry sources | Markdown-like with date headers |

## IPC Endpoints

| Endpoint | Type | URI/Action |
|----------|------|-----------|
| Habit list | ContentProvider | `content://com.example.tail.provider/habits` |
| Increment | BroadcastReceiver | `com.example.tail.ACTION_INCREMENT_HABIT` |
| Permission | Signature-level | `com.example.tail.permission.TAIL_INTEGRATION` |

## Development Environment

- **OS:** Linux 6.17
- **IDE:** Android Studio (VSCode also used)
- **Shell:** Bash
- **No venv needed** — This is a Kotlin/Android project (Gradle-based)
