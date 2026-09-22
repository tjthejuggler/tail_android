# Garmin Data Fetcher & Cached Proxy System

A rate-limit-aware system for continuously fetching Garmin health data and serving it to the Tail Android app via a cached proxy.

## Overview

This system solves the problem of Garmin API rate limits by:

1. **Fetching data gradually** - One metric at a time, with exponential backoff on rate limit errors
2. **Prioritizing intelligently** - 7 days back → current day → continuous updates
3. **Maintaining persistent state** - Tracks which data points have been successfully fetched
4. **Serving from cache** - The proxy serves pre-fetched data, making no direct Garmin API calls

## Architecture

```
┌─────────────────┐         ┌─────────────────┐         ┌─────────────────┐
│  Garmin Cloud   │         │  garmin_fetcher │         │  app_cached.py  │
│   (API)         │◄────────┤   (Background)  │───────► │   (Proxy)       │
└─────────────────┘         └─────────────────┘         └─────────────────┘
                                      │                          │
                                      │                          │
                                      ▼                          ▼
                               ┌─────────────┐           ┌─────────────┐
                               │  State &    │           │   Cache     │
                               │  Cache JSON │           │  (shared)   │
                               └─────────────┘           └─────────────┘
                                                                │
                                                                ▼
                                                      ┌─────────────────┐
                                                      │  Tail Android   │
                                                      │     App         │
                                                      └─────────────────┘
```

### Components

1. **garmin_fetcher.py** - Background daemon that:
   - Fetches one metric per date at a time
   - Implements exponential backoff for rate limits
   - Maintains persistent state of completed fetches
   - Prioritizes: 7 days back → current day → continuous updates
   - Builds up `garmin_cache.json` over time

2. **app_cached.py** - FastAPI proxy that:
   - Serves data from `garmin_cache.json`
   - Makes NO direct Garmin API calls
   - Provides endpoints for single date, batch, and availability queries

3. **garmin_fetcher_state.json** - Persistent state tracking:
   - Which metric/date pairs have been completed
   - Last continuous fetch timestamp
   - Rate limit error history

4. **garmin_cache.json** - Cached data in format:
   ```json
   {
     "vo2_max": {"2026-06-20": 45, "2026-06-19": 46, ...},
     "resting_hr": {"2026-06-20": 52, ...},
     ...
   }
   ```

## Installation

### Prerequisites

- Python 3.8+
- Garmin Connect account

### Install Dependencies

```bash
# Create virtual environment (if not exists)
python3 -m venv venv
source venv/bin/activate  # On Windows: venv\Scripts\activate

# Install dependencies
pip install fastapi uvicorn garminconnect
```

### Environment Variables

Set these environment variables:

```bash
export GARMIN_EMAIL="your.garmin.email@example.com"
export GARMIN_PASSWORD="your_garmin_password"
export ANDROID_PROXY_KEY="your_secure_secret_token_here"
```

## Usage

### 1. Start the Background Fetcher

Run the fetcher in the background (it will run continuously):

```bash
# Run in foreground for testing
python garmin_fetcher.py

# Run in background (nohup)
nohup python garmin_fetcher.py > garmin_fetcher.log 2>&1 &

# Or use systemd (recommended for production)
# See systemd service example below
```

The fetcher will:
1. First prioritize fetching data for the last 7 days
2. Then fetch current day metrics
3. Then continuously update current day metrics every 5 minutes

### 2. Start the Cached Proxy

```bash
# Run in foreground for testing
uvicorn garmin_proxy.app_cached:app --host 0.0.0.0 --port 8000

# Run in background
nohup uvicorn garmin_proxy.app_cached:app --host 0.0.0.0 --port 8000 > proxy.log 2>&1 &

# Production with workers
uvicorn garmin_proxy.app_cached:app --host 0.0.0.0 --port 8000 --workers 4
```

### 3. Configure Android App

Update your Android app to use the new proxy endpoints:

**Base URL:** `http://YOUR_PC_IP:8000`

**Headers:** `X-App-Auth: your_secure_secret_token_here`

## API Endpoints

### GET `/api/v1/health-metrics`

Get health metrics for a specific date from cache.

**Headers:**
- `X-App-Auth`: Your secret token

**Query Parameters:**
- `date`: ISO date string (YYYY-MM-DD). Defaults to today.

**Response:**
```json
{
  "date": "2026-06-20",
  "vo2_max": 45,
  "resting_hr": 52,
  "hrv_last_night": 68,
  "sleep_score": 85,
  "fitness_age": 28,
  "hrv_weekly_avg": 65,
  "steps": 10000,
  "altitude_ascent_meters": 150,
  "distance_meters": 8000,
  "calories": 2200,
  "active_minutes": 45,
  "floors_climbed": 12
}
```

### GET `/api/v1/health-metrics/batch`

Get health metrics for a range of dates.

**Headers:**
- `X-App-Auth`: Your secret token

**Query Parameters:**
- `start_date`: ISO date string (YYYY-MM-DD) - inclusive
- `end_date`: ISO date string (YYYY-MM-DD) - inclusive

**Response:**
```json
{
  "2026-06-15": { "vo2_max": 45, ... },
  "2026-06-16": { "vo2_max": 46, ... }
}
```

### GET `/api/v1/available-dates`

Get list of dates with cached data.

**Headers:**
- `X-App-Auth`: Your secret token

**Query Parameters:**
- `limit`: Maximum number of dates (default: 30)

**Response:**
```json
{
  "dates": ["2026-06-20", "2026-06-19", "2026-06-18", ...]
}
```

### GET `/api/v1/metrics-for-date/{date}`

Get summary of available metrics for a date.

**Headers:**
- `X-App-Auth`: Your secret token

**Response:**
```json
{
  "date": "2026-06-20",
  "available_metrics": ["vo2_max", "resting_hr", "steps"],
  "missing_metrics": ["hrv_last_night", "sleep_score"]
}
```

### GET `/api/v1/health-check`

Comprehensive health check.

**Headers:**
- `X-App-Auth`: Your secret token

**Response:**
```json
{
  "status": "healthy",
  "cache_file_exists": true,
  "cache_readable": true,
  "cached_dates_count": 7,
  "oldest_date": "2026-06-13",
  "newest_date": "2026-06-20",
  "metrics_tracked": 12
}
```

## Rate Limit Handling

The fetcher implements exponential backoff:

1. **Initial delay:** 60 seconds
2. **Subsequent delays:** Doubles each time (60s → 120s → 240s → ...)
3. **Maximum delay:** 1 hour (3600 seconds)
4. **Reset:** After a successful request, backoff resets to 60 seconds

This ensures we respect Garmin's rate limits while maximizing data collection over time.

## Fetch Priority

The fetcher follows a strict priority order:

1. **Phase 1 (Historical):** Fetch all metrics for the last 7 days, oldest first
2. **Phase 2 (Current Day):** Fetch all metrics for today
3. **Phase 3 (Continuous):** Cycle through today's metrics every 5 minutes for updates

## Systemd Service (Recommended)

Create `/etc/systemd/system/garmin-fetcher.service`:

```ini
[Unit]
Description=Garmin Data Fetcher
After=network.target

[Service]
Type=simple
User=your_username
WorkingDirectory=/path/to/tail
Environment="GARMIN_EMAIL=your.email@example.com"
Environment="GARMIN_PASSWORD=your_password"
ExecStart=/path/to/tail/venv/bin/python /path/to/tail/garmin_fetcher.py
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Create `/etc/systemd/system/garmin-proxy.service`:

```ini
[Unit]
Description=Garmin Cached Proxy
After=network.target

[Service]
Type=simple
User=your_username
WorkingDirectory=/path/to/tail
Environment="ANDROID_PROXY_KEY=your_secure_token"
ExecStart=/path/to/tail/venv/bin/uvicorn garmin_proxy.app_cached:app --host 0.0.0.0 --port 8000
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

Enable and start:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now garmin-fetcher
sudo systemctl enable --now garmin-proxy
sudo systemctl status garmin-fetcher
sudo systemctl status garmin-proxy
```

## Monitoring

### Check Fetcher Logs

```bash
tail -f garmin_fetcher.log
```

### Check Proxy Logs

```bash
tail -f proxy.log
```

### Check Cache State

```bash
cat garmin_fetcher_state.json | jq .
cat garmin_cache.json | jq .
```

### Check Systemd Services

```bash
journalctl -u garmin-fetcher -f
journalctl -u garmin-proxy -f
```

## Troubleshooting

### Rate Limit Errors

If you see frequent rate limit errors:
- Check that you're not running multiple fetcher instances
- The exponential backoff will automatically adjust
- Garmin rate limits reset daily, so it will improve over time

### No Data Available

If the proxy returns no data:
1. Check that `garmin_fetcher.py` is running
2. Check `garmin_cache.json` exists and has data
3. Check fetcher logs for errors
4. Verify Garmin credentials are correct

### Connection Issues

If the fetcher can't connect to Garmin:
1. Verify your Garmin credentials
2. Check your network connection
3. Garmin may be experiencing an outage

## Migration from Old System

The new system is compatible with the old `garmin_import.json` format:

1. **Stop the old proxy** (if running)
2. **Start the new fetcher** - it will begin building the cache
3. **Start the new proxy** - it will serve from the cache
4. **Update Android app** to use the new endpoints

The old `garmin_import.json` can be deleted once the new cache has sufficient data.

## Metrics Tracked

| Metric | Description | Source |
|--------|-------------|--------|
| `vo2_max` | Cardiovascular fitness score | Training Status |
| `resting_hr` | Resting heart rate (BPM) | Stats & Body |
| `hrv_last_night` | Last night's HRV (ms) | Sleep Data |
| `sleep_score` | Overall sleep quality (0-100) | Sleep Data |
| `fitness_age` | Biological age based on fitness | Fitness Age |
| `hrv_weekly_avg` | 7-day rolling HRV average | HRV Data |
| `steps` | Daily step count | Stats & Body |
| `altitude_ascent_meters` | Elevation climbed (meters) | Stats & Body |
| `distance_meters` | Total distance (meters) | Stats & Body |
| `calories` | Total kilocalories burned | Stats & Body |
| `active_minutes` | Moderate + vigorous minutes | Stats & Body |
| `floors_climbed` | Floors climbed | Stats & Body |

## Security

- Keep `ANDROID_PROXY_KEY` secret - it protects your proxy
- Store credentials in environment variables, not in code
- Use HTTPS in production (via reverse proxy like nginx)
- Consider using a VPN for remote access

## Performance

- **Fetch rate:** ~1 metric per 2 seconds (when not rate limited)
- **Time to fill 7 days:** ~28 minutes (12 metrics × 7 days × 2s)
- **Memory usage:** Minimal (< 50MB)
- **Disk usage:** ~1MB per year of cached data

## License

Part of the Tail Android project.

---

**Last Updated:** 2026-06-20T09:38:00Z