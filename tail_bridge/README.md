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

## API Endpoints

All endpoints require the `X-App-Auth` header (except `/health`).

| Method | Path | Description |
|--------|------|-------------|
| GET | `/health` | Server health (no auth) |
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
| `KDE_ACTIVITY_DB` | `~/.local/share/.../database` | KDE Activity SQLite path |
| `MOVIE_POLL_INTERVAL` | `60` | Watcher poll interval (seconds) |
| `MOVIE_CACHE_FILE` | `./movie_cache.json` | Cache output path |
| `GARMIN_CACHE_FILE` | `../garmin_proxy/garmin_cache.json` | Garmin metrics cache path |
| `STOCKFISH_PATH` | `/usr/games/stockfish` | Stockfish binary for chess analysis |
| `CHESS_ANALYSIS_NOTIFY` | `1` | Desktop notification (notify-send) after each live Stockfish analysis; `0` disables |
