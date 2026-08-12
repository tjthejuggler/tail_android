# Tail Desktop Services — PC↔Phone Infrastructure Guide

**Last updated:** 2026-08-12T14:40Z

This document explains the complete desktop-side infrastructure that supports
the Tail Android app's tethered features (Garmin health, movie tracking, and
future PC↔Phone integrations). It is written for future developers (human or AI)
who need to understand, maintain, or extend this system.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [The Supervisor (Single Autostart Entry)](#2-the-supervisor-single-autostart-entry)
3. [Service Registry (tail_services.toml)](#3-service-registry-tail_servicestoml)
4. [Managed Services](#4-managed-services)
5. [The Bridge Protocol (tail_bridge/)](#5-the-bridge-protocol-tail_bridge)
6. [Movie Tracking Pipeline](#6-movie-tracking-pipeline)
7. [Android Integration](#7-android-integration)
8. [Adding a New PC↔Phone Feature](#8-adding-a-new-pcphone-feature)
9. [Deployment & Reinstall](#9-deployment--reinstall)
10. [Troubleshooting](#10-troubleshooting)
11. [File Map](#11-file-map)

---

## 1. Architecture Overview

```
 ┌─────────────────────────── DESKTOP (Linux PC) ───────────────────────────┐
 │                                                                          │
 │  tail-supervisor.service  (the ONE systemd user service in autostart)    │
 │       │                                                                  │
 │       ▼                                                                  │
 │  tail_supervisor.py  (Python process manager, reads tail_services.toml)  │
 │       │                                                                  │
 │       ├──▶ garmin_proxy/app.py         FastAPI server  :8000             │
 │       ├──▶ garmin_proxy/fetch_data.py  Periodic (every 30 min + midnight)│
 │       ├──▶ tail_bridge/bridge_server.py  FastAPI server :8001            │
 │       └──▶ tail_bridge/movie_watcher.py  KDE Activity DB poller (60 s)   │
 │                                                                          │
 └──────────────────────────────────┬───────────────────────────────────────┘
                                    │ HTTP (local network Wi-Fi)
                                    │ X-App-Auth header for authentication
                                    ▼
 ┌─────────────────────────── PHONE (Android) ──────────────────────────────┐
 │                                                                          │
 │  BridgeClient.kt          Generic HTTP client (reusable for all sources) │
 │       │                                                                  │
 │       ├──▶ MovieBridgeService.kt   Movie-specific API calls              │
 │       └──▶ GarminService.kt        Garmin-specific API calls             │
 │                                                                          │
 │  HabitViewModel.kt         Orchestrates suggestions, polling, UI state   │
 │  SettingsScreen.kt         Bridge settings UI (URL, token, test conn)    │
 │  HabitGridScreen.kt        Movie Bridge toggle in habit edit panel       │
 │  TextInputDialog.kt        Pre-filled with suggested movie title         │
 │                                                                          │
 └──────────────────────────────────────────────────────────────────────────┘
```

### Key design principles

- **Single autostart entry**: Only `tail-supervisor.service` is in systemd. The
  supervisor manages all child processes. Adding a new service never requires
  touching autostart.
- **Source-registration pattern**: The bridge server auto-generates REST
  endpoints for each registered data source. New sources = one Python file.
- **Graceful degradation**: If the bridge is unreachable, the Android app
  silently falls back to normal behavior (no suggestion, no crash).

---

## 2. The Supervisor (Single Autostart Entry)

**File:** [`tail_supervisor.py`](tail_supervisor.py:1)
**Systemd unit:** [`tail-supervisor.service`](tail-supervisor.service:1)

The supervisor is a Python process manager that:

1. **Reads** [`tail_services.toml`](tail_services.toml:1) on startup
2. **Starts** all daemon services as child subprocesses
3. **Monitors** them every 5 seconds — restarts crashed daemons after `restart_sec`
4. **Schedules** periodic tasks (interval-based or daily calendar)
5. **Pipes** all child stdout → unified log (`supervisor.log` + journald)
6. **Handles** SIGTERM/SIGINT → graceful shutdown of all children

### CLI commands

```bash
# Show all configured services
python3 tail_supervisor.py --status

# One-shot health check (starts daemons, verifies they don't crash, stops them)
python3 tail_supervisor.py --check

# Run in foreground (for debugging)
python3 tail_supervisor.py
```

### systemd commands

```bash
systemctl --user status tail-supervisor.service
systemctl --user restart tail-supervisor.service
journalctl --user -u tail-supervisor.service -f
```

### How it works internally

- **Daemons**: Started with `subprocess.Popen`, output piped through a
  background thread to the log. The main loop calls `check_daemon()` every 5 s;
  if `poll()` returns non-None, the process crashed and is restarted after
  `restart_sec` delay.
- **Periodic tasks**: The main loop calls `should_run()` every 5 s. For
  interval tasks (`interval_min`), it checks elapsed time since last run. For
  schedule tasks (`schedule = "HH:MM"`), it checks if the current time is past
  the schedule and the task hasn't run yet today. Tasks execute in a background
  thread with a 900 s timeout. An `is_running` guard prevents concurrent
  execution of the same task.

### Venv resolution

Each service can have its own Python virtualenv. The supervisor auto-detects:
1. Explicit `venv` path in the TOML config
2. `{dir}/venv/bin/` (convention)
3. Falls back to system PATH

The venv's `bin/` is prepended to the child's `PATH`, so `python3`, `uvicorn`,
etc. resolve correctly.

---

## 3. Service Registry (tail_services.toml)

**File:** [`tail_services.toml`](tail_services.toml:1)

This TOML file is the **single source of truth** for what the supervisor manages.
Each `[[service]]` block defines one process.

### Fields

| Field | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Display name used in logs |
| `type` | Yes | `"daemon"` (long-running, auto-restart) or `"periodic"` (runs on schedule) |
| `dir` | Yes | Working directory (relative to project root) |
| `cmd` | Yes | Shell command (split with `shlex`) |
| `env_file` | No | Path to `.env` file (KEY=VALUE pairs loaded into environment) |
| `venv` | No | Path to virtualenv (defaults to `{dir}/venv`) |
| `restart_sec` | No | Seconds before restarting a crashed daemon (default: 10) |
| `interval_min` | No* | For periodic: run every N minutes |
| `schedule` | No* | For periodic: daily at `"HH:MM"` (e.g. `"00:05"`) |
| `delay_sec` | No | Initial delay before first periodic run (default: 0) |

\* Periodic services must specify either `interval_min` or `schedule`.

### Adding a new service

1. Add a `[[service]]` block to `tail_services.toml`
2. Run `systemctl --user restart tail-supervisor.service`

That's it. No new systemd files, no autostart changes.

---

## 4. Managed Services

Currently registered in `tail_services.toml`:

| Name | Type | Port/Interval | Description |
|------|------|---------------|-------------|
| `garmin-proxy` | daemon | :8000 | FastAPI server serving cached Garmin health data |
| `garmin-fetch` | periodic | 30 min | Fetches fresh Garmin data from the Garmin API |
| `garmin-fetch-midnight` | periodic | daily 00:05 | Force-refreshes 7 days of Garmin data at midnight |
| `tail-bridge` | daemon | :8001 | FastAPI server for PC↔Phone bridge (movies, future sources) |
| `movie-watcher` | daemon | 60 s poll | Polls KDE Activity Manager DB for new video plays |

### Garmin Proxy (`garmin_proxy/`)

- **`app.py`** — FastAPI server on port 8000. Serves cached health metrics from
  `garmin_cache.json`. Endpoints: `/api/v1/health-metrics`, `/api/v1/health-metrics-range`,
  `/api/v1/cache-info`, `/health`, `/api/v1/health-check`.
- **`fetch_data.py`** — Fetches data from Garmin Connect API via `garminconnect`
  library. Writes to `garmin_cache.json`. Run periodically.
- **`auth_bridge.py`** — One-time OAuth authentication for Garmin Connect.
- Auth: `X-App-Auth` header with token from `.env` (`ANDROID_PROXY_KEY`).
- Android client: [`GarminService.kt`](app/src/main/java/com/example/tail/data/GarminService.kt:1)

### Tail Bridge (`tail_bridge/`)

See [§5 below](#5-the-bridge-protocol-tail_bridge).

---

## 5. The Bridge Protocol (tail_bridge/)

The Tail Bridge is a **source-registration system** that generalises the Garmin
proxy pattern. Instead of hard-coding endpoints, the bridge server auto-generates
REST endpoints for each registered data source.

### Source Registration

Each source implements the `BridgeSource` ABC from [`sources/base.py`](tail_bridge/sources/base.py:1):

```python
class BridgeSource(ABC):
    name: str           # e.g. "movies"
    description: str    # human-readable

    @abstractmethod
    def get_latest(self) -> dict | None:
        """Return the most recent entry, or None."""

    @abstractmethod
    def get_recent(self, limit: int = 10) -> list[dict]:
        """Return recent entries."""

    @abstractmethod
    def health(self) -> dict:
        """Return health status."""
```

Sources are registered in [`sources/__init__.py`](tail_bridge/sources/__init__.py:1).
The server auto-generates:

| Endpoint | Description |
|----------|-------------|
| `GET /api/v1/{source}/latest` | Most recent entry |
| `GET /api/v1/{source}/recent?limit=N` | Recent N entries |
| `GET /api/v1/{source}/health` | Source health check |
| `GET /health` | Overall server health |

The movie source also has a custom endpoint:
`GET /api/v1/movies/suggest?exclude=Title1,Title2` — returns the latest movie
not in the exclude list.

### Authentication

All endpoints require an `X-App-Auth` header matching the `ANDROID_PROXY_KEY`
in the `.env` file. The same token is used by the Garmin proxy for convenience.

---

## 6. Movie Tracking Pipeline

```
KDE Activity Manager DB (SQLite)
    │  ~/.local/share/kactivitymanagerd/resources/database
    │  Table: ResourceEvent(targettedResource, start, end)
    │
    ▼
movie_watcher.py  (polls every 60 s, high-water-mark tracking)
    │  1. Queries new rows since last_seen_start
    │  2. Extracts filepath from targettedResource
    │  3. Cleans filename → title via movie_name_cleaner.py
    │  4. Groups by (date, title), merges multiple sessions
    │  5. Writes to movie_cache.json
    │
    ▼
movie_cache.json  (flat JSON array of entries)
    │  Each entry: {date, title, raw_file, is_series, season, episode,
    │               sessions: [{start, end, duration_min}, ...],
    │               total_duration_min}
    │
    ▼
bridge_server.py  (FastAPI on :8001)
    │  Serves /api/v1/movies/latest, /recent, /suggest
    │
    ▼  HTTP (local network)
Android app  (BridgeClient → MovieBridgeService → HabitViewModel → TextInputDialog)
```

### Session tracking

If a film is started, stopped, and restarted, each viewing segment is preserved
as a separate session in the `sessions` array. The `total_duration_min` field
sums all sessions. This was explicitly requested by the user.

### movie_name_cleaner.py

Reusable filename→title parser extracted from the original
`clean_video_history.py` script. Handles:
- TV shows: `Series.Name.S01E05.1080p.mkv` → `"Series Name", S1 E5`
- Movies with year: `Movie.Title.2023.720p.mkv` → `"Movie Title (2023)"`
- Quality tag stripping: removes `1080p`, `720p`, `x264`, `WEB-DL`, etc.
- Separator normalization: dots, underscores, dashes → spaces
- Acronym fixing: `S H I E L D` → `SHIELD`

Public API: `clean_filename(filepath) → MovieInfo(title, season, episode, raw)`

---

## 7. Android Integration

### Data layer

| File | Role |
|------|------|
| [`BridgeClient.kt`](app/src/main/java/com/example/tail/data/BridgeClient.kt:1) | Generic HTTP client. `fetch(url, token, path)` returns `JSONObject?`. `checkHealth(url, token)` returns `Boolean`. Reusable for ALL future bridge sources. |
| [`MovieBridgeService.kt`](app/src/main/java/com/example/tail/data/MovieBridgeService.kt:1) | Movie-specific service. Data classes: `BridgeMovie` (title, isSeries, season, episode, sessions, durationLabel), `MovieSession`. Methods: `fetchLatestSuggestion()`, `fetchLatest()`, `fetchRecent()`, `testConnection()`. |

### Settings

Persisted in DataStore via [`SettingsRepository.kt`](app/src/main/java/com/example/tail/data/SettingsRepository.kt:1):

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `bridgeEnabled` | Boolean | false | Master toggle for bridge features |
| `bridgeUrl` | String | "" | Bridge server URL (e.g. `http://192.168.1.100:8001`) |
| `bridgeToken` | String | "" | X-App-Auth token (same as Garmin proxy) |
| `bridgeMovieHabits` | Set<String> | empty | Habit names linked to movie suggestions |

Defined in [`AppSettings`](app/src/main/java/com/example/tail/data/HabitModels.kt:266).

### UI flow

1. **Settings → Bridge section** ([`SettingsScreen.kt`](app/src/main/java/com/example/tail/ui/SettingsScreen.kt:1)):
   Enable bridge, enter URL + token, Test Connection button, see linked habits.

2. **Habit edit panel** ([`HabitGridScreen.kt`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:2481)):
   For text-input habits when bridge is enabled, a "🎬 Movie Bridge" toggle
   appears. Toggling it links the habit to movie suggestions.

3. **Habit tap flow** (when a movie-linked text-input habit is tapped):
   - App fetches today's already-logged entries (to exclude them)
   - Calls `GET /api/v1/movies/suggest?exclude=Title1,Title2`
   - Pre-fills [`TextInputDialog`](app/src/main/java/com/example/tail/ui/TextInputDialog.kt:67)
     with the suggested movie title
   - Shows "🎬 Suggested from desktop" label in green
   - User can confirm, edit, or clear the suggestion
   - If bridge is unreachable, dialog opens normally with no suggestion

### ViewModel methods

In [`HabitViewModel.kt`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:1):

| Method | Description |
|--------|-------------|
| `saveBridgeSettings(enabled, url, token)` | Persists bridge settings |
| `toggleBridgeMovieHabit(habitName)` | Toggles a habit's movie bridge link |
| `fetchMovieSuggestion(excludeTitles, onResult)` | Fetches latest movie suggestion |
| `clearMovieSuggestion()` | Clears the suggestion state |
| `testBridgeConnection()` | Tests connectivity, updates `_bridgeStatus` |

### Important: EditModeControlBar method-size limit

[`EditModeControlBar`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:2481) is a
~1800-line Compose function with ~90 parameters that was already at the JVM 64KB
bytecode limit. Adding bridge parameters required extracting inline UI into
separate composables:

- [`GarminLinkToggleSection`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:5638) — extracted Garmin toggle UI
- [`MovieBridgeToggleSection`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:5707) — extracted Movie Bridge toggle UI
- The Movie Bridge toggle is passed as a `@Composable () -> Unit` lambda (`movieBridgeContent`) to avoid adding parameters

**If you need to add more inline UI to `EditModeControlBar`, extract it into a
separate composable first.** The existing `RestoreFromBackupButton` pattern is
the template.

---

## 8. Adding a New PC↔Phone Feature

End-to-end guide for adding a new tethered feature (e.g., music tracking,
book tracking, screen time, etc.).

### Step 1: Desktop — Create the data source

1. Create `tail_bridge/sources/<name>.py`:

```python
from .base import BridgeSource

class MyFeatureSource(BridgeSource):
    name = "myfeature"
    description = "Description of my feature"

    def get_latest(self) -> dict | None:
        # Return the most recent data entry
        ...

    def get_recent(self, limit: int = 10) -> list[dict]:
        # Return recent entries
        ...

    def health(self) -> dict:
        return {"status": "healthy", "entries": 42}
```

2. Register in `tail_bridge/sources/__init__.py`:

```python
from .myfeature import MyFeatureSource

def get_all_sources() -> list[BridgeSource]:
    return [
        MovieSource(),
        MyFeatureSource(),   # ← add here
    ]
```

3. If the source needs a watcher daemon, create `tail_bridge/<name>_watcher.py`
   and add a `[[service]]` block to `tail_services.toml`.

### Step 2: Desktop — Test

```bash
cd tail_bridge
source venv/bin/activate
python bridge_server.py
# In another terminal:
curl -H "X-App-Auth: YOUR_TOKEN" http://localhost:8001/api/v1/myfeature/latest
```

### Step 3: Android — Create the service

Create `app/src/main/java/com/example/tail/data/MyFeatureBridgeService.kt`:

```kotlin
class MyFeatureBridgeService(private val client: BridgeClient) {
    suspend fun fetchLatest(url: String, token: String): MyFeatureData? {
        val json = client.fetch(url, token, "/api/v1/myfeature/latest") ?: return null
        // Parse JSON into your data class
        ...
    }
}
```

### Step 4: Android — Wire into ViewModel and UI

- Add settings fields to `AppSettings` + `SettingsRepository`
- Add ViewModel methods (follow the `saveBridgeSettings` / `fetchMovieSuggestion` pattern)
- Add UI in `SettingsScreen.kt` and/or `HabitGridScreen.kt`
- **Remember**: Extract any new `EditModeControlBar` UI into a separate composable

### Step 5: Restart and deploy

```bash
# Desktop: restart supervisor to pick up new TOML entries
systemctl --user restart tail-supervisor.service

# Android: build and deploy
./gradlew installDebug
```

---

## 9. Deployment & Reinstall

### First-time setup (or reinstall on a new computer)

```bash
cd ~/AndroidStudioProjects/tail

# 1. Create .env files from examples
cp garmin_proxy/.env.example garmin_proxy/.env
cp tail_bridge/.env.example tail_bridge/.env
# Edit both .env files with your actual credentials

# 2. Run the installer (creates venvs, installs deps, sets up supervisor)
bash install_supervisor.sh

# 3. For Garmin: do one-time OAuth authentication
cd garmin_proxy
source venv/bin/activate
python3 auth_bridge.py
```

### What the installer does

1. Creates Python venvs for `garmin_proxy/` and `tail_bridge/` (if missing)
2. Installs `requirements.txt` dependencies in each venv
3. Stops and disables ALL old individual systemd services
4. Removes old `.service` unit files from `~/.config/systemd/user/`
5. Installs `tail-supervisor.service` as the single systemd user service
6. Enables and starts it

### On a new computer

The only things you need:
1. The project directory (git clone or copy)
2. `.env` files with credentials (not in git)
3. Run `bash install_supervisor.sh`

Everything else is automatic.

---

## 10. Troubleshooting

### Supervisor won't start

```bash
# Check status
systemctl --user status tail-supervisor.service

# Check logs
journalctl --user -u tail-supervisor.service -n 50

# Run in foreground for debugging
python3 tail_supervisor.py
```

### A daemon keeps crashing

The supervisor auto-restarts crashed daemons. Check the log for the exit code:
```
[garmin-proxy] ✗ Exited (rc=1), restart in 10s (crash #3)
```

Common causes:
- **Missing .env file**: Create from `.env.example`
- **Missing venv**: Run `bash install_supervisor.sh`
- **Port already in use**: Check for orphan processes: `lsof -i :8000` / `lsof -i :8001`
- **Garmin auth expired**: Re-run `python3 auth_bridge.py` in `garmin_proxy/`

### Periodic task not running

- Check `tail_services.toml` syntax (valid TOML?)
- For schedule tasks: the task runs once per day after the scheduled time. If
  the computer was off, it runs on next boot.
- For interval tasks: check `delay_sec` — there may be an initial delay.

### Android can't connect to bridge

1. Verify the bridge server is running: `curl -H "X-App-Auth: TOKEN" http://DESKTOP_IP:8001/health`
2. Verify the phone and computer are on the same Wi-Fi network
3. Check the URL in Settings → Bridge section (should be `http://DESKTOP_IP:8001`)
4. Check the token matches the `.env` file's `ANDROID_PROXY_KEY`
5. Check firewall: `sudo ufw allow from 192.168.1.0/24 to any port 8001`

### Movie suggestions not appearing

1. Verify movie_watcher is running and has data:
   ```bash
   cat tail_bridge/movie_cache.json | python3 -m json.tool | head -20
   ```
2. Verify the habit is text-input AND movie-bridge-linked (check edit panel)
3. Verify `bridgeEnabled` is true in Android settings
4. Check that today's entries aren't excluding all movies (the suggest endpoint
   skips already-logged titles)

---

## 11. File Map

### Desktop — Supervisor

| File | Description |
|------|-------------|
| [`tail_supervisor.py`](tail_supervisor.py:1) | Process supervisor (manages all services) |
| [`tail_services.toml`](tail_services.toml:1) | Service registry config |
| [`tail-supervisor.service`](tail-supervisor.service:1) | systemd user service unit |
| [`install_supervisor.sh`](install_supervisor.sh:1) | One-command installer |
| `supervisor.log` | Unified log output (auto-generated) |

### Desktop — Garmin Proxy

| File | Description |
|------|-------------|
| [`garmin_proxy/app.py`](garmin_proxy/app.py:1) | FastAPI server (port 8000) |
| [`garmin_proxy/fetch_data.py`](garmin_proxy/fetch_data.py:1) | Garmin API data fetcher |
| [`garmin_proxy/auth_bridge.py`](garmin_proxy/auth_bridge.py:1) | OAuth authentication |
| `garmin_proxy/garmin_cache.json` | Cached health data |
| `garmin_proxy/.env` | Credentials (GARMIN_EMAIL, GARMIN_PASSWORD, ANDROID_PROXY_KEY) |

### Desktop — Tail Bridge

| File | Description |
|------|-------------|
| [`tail_bridge/bridge_server.py`](tail_bridge/bridge_server.py:1) | FastAPI server (port 8001) |
| [`tail_bridge/movie_watcher.py`](tail_bridge/movie_watcher.py:1) | KDE Activity DB poller daemon |
| [`tail_bridge/movie_name_cleaner.py`](tail_bridge/movie_name_cleaner.py:1) | Filename→title parser |
| [`tail_bridge/sources/base.py`](tail_bridge/sources/base.py:1) | BridgeSource ABC |
| [`tail_bridge/sources/movies.py`](tail_bridge/sources/movies.py:1) | Movie source implementation |
| [`tail_bridge/sources/__init__.py`](tail_bridge/sources/__init__.py:1) | Source registry |
| `tail_bridge/movie_cache.json` | Movie tracking cache |
| `tail_bridge/.env` | Bridge credentials (ANDROID_PROXY_KEY, TAIL_BRIDGE_PORT) |
| [`tail_bridge/README.md`](tail_bridge/README.md:1) | Bridge-specific documentation |

### Android — Bridge integration

| File | Description |
|------|-------------|
| [`app/.../data/BridgeClient.kt`](app/src/main/java/com/example/tail/data/BridgeClient.kt:1) | Generic HTTP client |
| [`app/.../data/MovieBridgeService.kt`](app/src/main/java/com/example/tail/data/MovieBridgeService.kt:1) | Movie-specific service |
| [`app/.../data/HabitModels.kt`](app/src/main/java/com/example/tail/data/HabitModels.kt:1) | AppSettings (bridge fields) |
| [`app/.../data/SettingsRepository.kt`](app/src/main/java/com/example/tail/data/SettingsRepository.kt:1) | DataStore persistence |
| [`app/.../ui/HabitViewModel.kt`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:1) | Bridge methods + state |
| [`app/.../ui/SettingsScreen.kt`](app/src/main/java/com/example/tail/ui/SettingsScreen.kt:1) | BridgeSettingsSection UI |
| [`app/.../ui/HabitGridScreen.kt`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:1) | Movie Bridge toggle + suggestion flow |
| [`app/.../ui/TextInputDialog.kt`](app/src/main/java/com/example/tail/ui/TextInputDialog.kt:1) | Pre-filled text dialog |

### Old systemd service files (no longer used, kept for reference)

These files still exist in their directories but are NOT installed by the
supervisor installer. The supervisor replaces them all:

- `garmin_proxy/garmin-proxy.service`
- `garmin_proxy/garmin-fetch.service` + `.timer`
- `garmin_proxy/garmin-fetch-midnight.service` + `.timer`
- `tail_bridge/tail-bridge.service`
- `tail_bridge/movie-watcher.service`
- `garmin_proxy/install_autostart.sh` (old installer, superseded by `install_supervisor.sh`)
