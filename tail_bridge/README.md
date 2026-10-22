# Tail Bridge — PC↔Phone Communication Protocol

The Tail Bridge is a unified server that tethers desktop data to the Tail Android
app. It generalises the Garmin proxy pattern into a reusable **source-registration
system** so that adding new tethered features (movies, music, books, …) requires
minimal boilerplate.

## Architecture

```
 ┌─────────────────────────── DESKTOP ───────────────────────────┐
 │                                                                │
 │  movie_watcher.py          future_watcher.py                   │
 │  (KDE Activity DB poll)    (other data source)                 │
 │       │                          │                             │
 │       ▼                          ▼                             │
 │  movie_cache.json          future_cache.json                   │
 │       │                          │                             │
 │       └──────────┬───────────────┘                             │
 │                  ▼                                              │
 │         ┌──────────────┐                                       │
 │         │ bridge_server│  FastAPI on :8001                     │
 │         │   .py        │  X-App-Auth header                    │
 │         └──────┬───────┘                                       │
 └────────────────┼───────────────────────────────────────────────┘
                  │ HTTP (local network)
                  ▼
 ┌─────────────────────────── PHONE ─────────────────────────────┐
 │                                                                │
 │  BridgeClient.kt     (generic HTTP client)                     │
 │       │                                                        │
 │       ├── MovieBridgeService.kt  (movie suggestions)           │
 │       └── FutureService.kt       (future features)             │
 │       │                                                        │
 │       ▼                                                        │
 │  HabitViewModel → pre-fills text entries, etc.                 │
 └────────────────────────────────────────────────────────────────┘
```

## Quick Start

```bash
cd tail_bridge
./setup.sh
# Edit .env to set ANDROID_PROXY_KEY

# Start manually (for testing):
venv/bin/python movie_watcher.py --once   # one-shot backfill
venv/bin/uvicorn bridge_server:app --host 0.0.0.0 --port 8001

# Or install as systemd services:
mkdir -p ~/.config/systemd/user
cp tail-bridge.service movie-watcher.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now tail-bridge.service movie-watcher.service
```

## Two Bridges: Laptop (`full`) and VPS (`remote`)

The bridge runs in one of two roles, selected by `TAIL_BRIDGE_ROLE`:

| Role | Where | Sources | Purpose |
|------|-------|---------|---------|
| `full` (default) | laptop | movies, garmin, tailcue | Everything, including laptop-local features (movie watching, PC bubble widget) |
| `remote` | RackNerd VPS (`tailcue-1`, `100.64.91.104`) | garmin, tailcue | Always-on, device-independent work: daily Garmin import, TailCue cue delivery, phone backups |

The `remote` role deliberately **omits the movies source** — movie data only
exists on the laptop. Everything else is duplicated so the phone keeps working
when the laptop is off.

The Android app stores an optional **Remote server URL** (`remoteHostUrl`,
Settings → Bridge). When set, the device-independent paths try the VPS first
and fall back to the laptop:

* `GarminSyncWorker` → VPS garmin proxy (`:8000`) first, then the configured proxy
* `TailCueSyncWorker` / `TailCueFeedback` → VPS bridge (`:8001`) first
* `BridgeBackupManager` → VPS bridge first, then saved/Garmin-derived/LAN URLs

Movies and the PC bubble widget always use the laptop bridge.

### VPS deployment (systemd)

| Unit | Command |
|------|---------|
| `tail-bridge-vps.service` | `uvicorn bridge_server:app --host 100.64.91.104 --port 8001` |
| `garmin-proxy-vps.service` | `uvicorn app:app --host 100.64.91.104 --port 8000` |
| `garmin-fetch-vps.timer` | `fetch_data.py` every 30 min (OnBootSec=5min) |

Bind uvicorn **directly to the Tailscale IP** — `tailscale serve` routes by
`Host` header, so raw-IP requests to a served port return `404 page not found`.
Garmin OAuth tokens live in `~/.garminconnect/garmin_tokens.json` on the VPS
(copied from the laptop); `fetch_data.py` is token-based and never logs in.

### Laptop: only `tail-supervisor.service` may own port 8001

The laptop has **two** user units that can start the bridge:

| Unit | Enabled at boot? | Owner |
|------|------------------|-------|
| `tail-supervisor.service` | **yes** | supervises all six services (bridge, garmin proxy, movie watcher, …) |
| `tail-bridge.service` | no (legacy) | starts uvicorn alone |

`tail-bridge.service` is a leftover from before the supervisor existed. It is
`disabled`, so it does not start at boot — but if it is ever started by hand it
grabs `127.0.0.1:8001` and every supervisor child then dies with
`[Errno 98] address already in use` (visible as a rising `crash #N` counter in
`journalctl --user -u tail-supervisor.service`). Worse, the surviving process
serves **stale code**, so `.env` edits appear to have no effect.

When a config change seems ignored, check for a stray listener first:

```bash
ss -ltnp | grep ':8001'          # who owns the port?
systemctl --user is-active tail-bridge.service   # should be: inactive
```

If `tail-bridge.service` is active, `systemctl --user stop tail-bridge.service`
and restart the supervisor. To remove the footgun permanently:

```bash
systemctl --user disable --now tail-bridge.service
rm ~/.config/systemd/user/tail-bridge.service
systemctl --user daemon-reload
```

Note that `systemctl --user stop tail-supervisor.service` does **not** kill an
already-orphaned uvicorn — the supervisor only stops children it spawned. Kill
the stray pid explicitly when simulating a laptop-off state.

## PC Dashboard

The bridge serves a status dashboard on the PC — open
`http://localhost:8001/` in any browser. It shows:

* **Connections** — bridge server status/uptime, when the Android app was
  last seen (and what it last requested), when the PC bubble widget last
  polled.
* **Devices** — every device that has contacted this bridge recently, with a
  connected/offline dot, the last request, and how long ago: 📱 phone,
  🫧 PC widget, 🧠 TailCue server, ☁️ peer bridge. Online thresholds are
  per-device (`DEVICE_ONLINE_SEC`: phone 45 min, pc 5 min, tailcue 8 h) because
  their natural polling cadences differ. The peer-bridge row comes from a
  cached (30 s TTL) `/health` probe of `PEER_BRIDGE_URL`, so each dashboard
  shows the other bridge's status too.
* **Role pill + cross-link** — the header shows `💻 laptop` or
  `☁️ remote server` and, when `TAILCUE_APP_URL` is set, an
  **Open TailCue ↗** link. The TailCue webapp has the mirror-image
  **Open TailBridge ↗** link (from `TAILCUE_BRIDGE_APP_URL`).
* **Android app → PC widget** — which habits are currently toggled ON in the
  app for the PC widget (with `minutes_primary` / divider / inverted /
  no-points badges) and when the phone last pushed the config.
* **Pending event queue** — widget events waiting for the phone to ack,
  each with an ✎ edit button (pending events are fixed in place before
  the phone ever sees them).
* **PC widget history** — every widget event the bridge has ever queued
  (`~/.config/tail_bridge/pc_widget_history.json`), separate from the
  other histories, with synced/pending state and edited/deleted badges.
  Every session/tap row has ✎ edit and delete buttons.
* **Cline agent · live feed** — what the headless Cline CLI agent (packd,
  the Pack app's build backend on the VPS) is doing right now: the active
  job's project/instruction, its streaming agent-output log (tail, updates
  live while Cline runs), the live `cline --json` process (pid + uptime),
  and the 5 most recent pack jobs with status/summary. Hidden when neither
  this bridge nor its peer can see a packd jobs DB (`PACKD_JOBS_DB`,
  default `/srv/packd/jobs.db`, read-only).
* **Live activity** — every API request since server start (who, what, how
  long). Requests are classified by origin: loopback → PC, LAN → phone.
* **History** — a persistent, restart-surviving "what did what when" log
  (`~/.config/tail_bridge/bridge_history.json`): config pushes, queued
  widget sessions/taps, phone acks, live Stockfish analyses, server starts.
* **Sources** — recent movie watches, the latest Garmin metrics, and chess
  analysis history from the SQLite registry. The Garmin card also shows the
  current connection status (last fetch, last cache sync, rate-limit
  cooldown, OAuth token health, whether today's data is present, live fetch
  state) and has a **⟳ Sync today now** button that force-runs
  `garmin_proxy/fetch_data.py` in the background (token-based — it never
  logs in) to refresh the cache the bridge serves.

The page polls one aggregated endpoint, `GET /api/v1/dashboard`, every 5 s —
no token needed: the backend authorizes the dashboard itself and serves it
only to private-network clients (loopback / RFC1918 LAN / Tailscale CGNAT),
never to arbitrary public addresses. Long sections scroll in place so the
page stays compact.

The dashboard controls are PC-widget events (via the popup
editor — the replacement for the bubble widget's old right-click "Today's
history" dialog, which has been removed):

* **pending event** → edited/deleted **in place** (the phone never applied
  it, so no correction is needed);
* **already-acked event** → a `session_edit` / `session_delete`
  **correction** is queued carrying `ref_id` + `orig` (the values the phone
  did apply), which the phone inverts before applying the fix — the exact
  same contract as `pc_widget_sync.append_correction_event`.

* **Garmin sync** — the ⟳ button POSTs to `/api/v1/dashboard/garmin_fetch`,
  which spawns `fetch_data.py --days 1 --force` in a background thread using
  the garmin_proxy venv; the outcome (ok/error + message tail) is recorded
  in `garmin_proxy/fetch_status.json` and shown in the Garmin status rows on
  the next 5 s poll.

Everything else on the page is informational only.

## API Endpoints

All endpoints require the `X-App-Auth` header (except `/health`).

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Server health incl. `role` + `device_name` (no auth) |
| GET | `/` | PC dashboard UI (no auth) |
| POST | `/api/v1/heartbeat` | Device presence ping — body `{"device": "phone"\|"tailcue"\|…}` (auth required) |
| GET | `/api/v1/dashboard` | Aggregated snapshot incl. `pc_widget_history` (private-network clients only, no token) |
| POST | `/api/v1/dashboard/widget_edit` | Edit a widget event — in place if pending, else queue a `session_edit` correction (no token, private network only) |
| POST | `/api/v1/dashboard/widget_delete` | Delete a widget event — drop if pending, else queue a `session_delete` correction (no token, private network only) |
| POST | `/api/v1/dashboard/garmin_fetch` | Force a Garmin fetch/sync — runs `garmin_proxy/fetch_data.py` in the background and refreshes the cache (no token, private network only) |
| GET | `/api/v1/sources` | List registered sources |
| GET | `/api/v1/movies/latest` | Most recently watched movie/series |
| GET | `/api/v1/movies/recent?limit=10` | N most recent movies |
| GET | `/api/v1/movies/suggest?exclude=Title1,Title2` | Latest movie not in exclude list |
| GET | `/api/v1/movies/health` | Movie source health |
| GET | `/api/v1/garmin/latest` | Most recent day's Garmin health metrics |
| GET | `/api/v1/garmin/recent?limit=10` | N most recent days of Garmin metrics |
| GET | `/api/v1/garmin/health` | Garmin source health (cache status) |
| GET/POST | `/api/v1/pc_widget/config` | PC bubble widget habit squares (phone pushes) |
| POST | `/api/v1/pc_widget/event` | Queue one PC habit event (bridge assigns ID) |
| GET | `/api/v1/pc_widget/events` | PC habit events not yet acked by the phone |
| POST | `/api/v1/pc_widget/acks` | Phone acks; the bridge prunes acked events |
| GET | `/api/v1/chess_analysis/status` | Tail-owned Stockfish analysis status |
| POST | `/api/v1/chess_analysis/analyze` | Analyse a PGN → per-side blunder/ACPL stats |

### Example Response (`/api/v1/movies/latest`)

```json
{
  "datetime": "2026-02-11 17:07:51",
  "date": "2026-02-11",
  "title": "All Her Fault S01E02",
  "season": 1,
  "episode": 2,
  "raw": "All.Her.Fault.S01E02.1080p.WEB-DL.mkv"
}
```

## Chess Analysis (Tail-owned Stockfish)

The bridge runs its **own** Stockfish analysis for the Tail app's chess
readiness verdicts — the Tail bundle (phone app + bridge) is fully standalone
and does **not** require the chess-coach program. Dependencies:

```bash
pip install -r requirements.txt   # includes python-chess
sudo apt install stockfish        # default path /usr/games/stockfish
```

- **`POST /api/v1/chess_analysis/analyze`** — body
  `{pgn, game_id?, username?, depth?}` (default depth 12, max 18). Runs a
  single-pass Stockfish analysis (~1–2 s/game) and returns per-side
  `acpl / blunders / mistakes / inaccuracies / unforced_blunders / moves`
  plus the resolved `user_side`.
- **Dedup**: results are cached in `data/chess_analysis.db` (SQLite) keyed by
  the canonical game id (PGN `Link` header). A repeat request returns
  instantly with `cached: true` — a game is never analysed twice.
- **Chess-coach handoff (optional courtesy)**: every fresh analysis is pushed
  best-effort (fire-and-forget, 3 s timeout) to chess-coach's `/ingest`
  endpoint when it happens to be running, so chess-coach never re-analyses
  those games. If chess-coach isn't running, the push is silently skipped —
  the Tail system doesn't care.

Env knobs: `STOCKFISH_PATH`, `CHESS_ANALYSIS_DB`, `CHESS_ANALYSIS_THREADS`
(default `min(4, cpus)` — modest, the PC may run other engines),
`CHESS_ANALYSIS_HASH_MB` (default 128), `CHESS_COACH_INGEST_URL`
(set empty to disable the handoff).

## Adding a New Source (Future Feature)

### Desktop side

1. Create `sources/my_thing.py`:

```python
from .base import BridgeSource

class MyThingSource(BridgeSource):
    @property
    def name(self): return "my_thing"

    @property
    def description(self): return "My cool data"

    def get_latest(self):
        # Return a dict or None
        ...

    def get_recent(self, limit=10):
        # Return a list of dicts
        ...

    def health(self):
        return {"status": "ok"}
```

2. Register in `sources/__init__.py`:

```python
from .my_thing import MyThingSource
sources.append(MyThingSource())
```

Done. The server auto-generates `/api/v1/my_thing/latest`, `/recent`, `/health`.

### Android side

1. Add a data class for the response.
2. Call `bridgeClient.fetch("my_thing/latest", parser)`.
3. Hook into the UI.

## Components

| File | Purpose |
|------|---------|
| `movie_name_cleaner.py` | Reusable filename → clean title parser |
| `movie_watcher.py` | KDE Activity DB poller daemon |
| `bridge_server.py` | Unified FastAPI server |
| `dashboard.py` | Read-only dashboard: snapshot endpoint, activity middleware, history log |
| `static/dashboard.html` | Single-file dashboard UI (dark theme, vanilla JS) |
| `sources/base.py` | BridgeSource abstract interface |
| `sources/movies.py` | Movie data source implementation |
| `sources/garmin.py` | Garmin health-metrics source (reads `garmin_cache.json`) |
| `sources/__init__.py` | Source registry |

## Auto-Connection (No Manual Setup)

The bridge runs on the **same PC** as the Garmin proxy and shares the same
`ANDROID_PROXY_KEY`. The Android app auto-derives the bridge URL from the
Garmin proxy URL (same host, port 8001 instead of 8000) and reuses the same
auth token. **No separate bridge URL or token configuration is needed** — just
enable the bridge toggle in Settings and it connects automatically.

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ANDROID_PROXY_KEY` | (required) | Shared secret for X-App-Auth (same as Garmin proxy) |
| `TAIL_BRIDGE_PORT` | `8001` | Server port |
| `TAIL_BRIDGE_ROLE` | `full` | `full` = laptop (all sources); `remote` = VPS (no movies) |
| `TAIL_BRIDGE_DEVICE_NAME` | `laptop` / `server` | Shown in `/health` and the dashboard header |
| `TAILCUE_APP_URL` | (empty) | TailCue webapp URL → renders the **Open TailCue ↗** header link |
| `PEER_BRIDGE_URL` | (empty) | Other bridge's base URL → adds a peer-bridge row to the devices panel |
| `KDE_ACTIVITY_DB` | `~/.local/share/.../database` | KDE Activity SQLite path |
| `MOVIE_POLL_INTERVAL` | `60` | Watcher poll interval (seconds) |
| `MOVIE_CACHE_FILE` | `./movie_cache.json` | Cache output path |
| `GARMIN_CACHE_FILE` | `../garmin_proxy/garmin_cache.json` | Garmin metrics cache path |
| `STOCKFISH_PATH` | `/usr/games/stockfish` | Stockfish binary for chess analysis |
| `CHESS_ANALYSIS_NOTIFY` | `1` | Desktop notification (notify-send) after each live Stockfish analysis; `0` disables |

## 2026-09-26 — Garmin sync outage: root cause & fix

Symptom: everything synced except Garmin. Root cause was **phone-side**: the
app only fetched Garmin metrics while the main UI was open (foreground hook +
a viewModelScope polling loop that was only started when settings were saved
and died with the Activity). Movies / PC-widget sync used WorkManager workers
that run regardless — hence the split behaviour. The PC bridge and Garmin
proxy were healthy throughout.

Fix: new `GarminSyncWorker` (2 h periodic, network-constrained) keeps the
phone-local Garmin cache fresh even when the app is closed; scheduled from
`MainActivity` next to `MovieSyncWorker`. Guardrails: skip-reasons in the app
are now logged (never silent), and the bridge's `/api/v1/garmin/health` now
reports `status: "stale"` + `stale_days` when the cache's newest day is >1 day
old, so the dashboard flags a dead fetch pipeline in red instead of "ok".
