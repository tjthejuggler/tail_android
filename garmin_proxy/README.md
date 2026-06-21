# Garmin Proxy for Tail Android App

A secure Python proxy that bridges Garmin Connect data to the Tail Android habit tracker app. Authentication uses the `garminconnect` / `garth` library with **persisted OAuth tokens** so the system logs in exactly once and reuses the tokens for every subsequent fetch.

**Last Updated:** 2026-06-21

## Architecture

This proxy uses a **token-based, three-stage architecture**:

1. **Stage 1 (Auth Bridge):** [`auth_bridge.py`](auth_bridge.py) logs in to Garmin Connect **once** and saves the OAuth1/OAuth2 tokens to a local token store (default `~/.garminconnect`). Supports MFA/2FA via an interactive terminal prompt.

2. **Stage 2 (Data Fetching):** [`fetch_data.py`](fetch_data.py) **resumes from the saved tokens** (no login), fetches the last 7 days of health metrics, and caches them locally in `garmin_cache.json`. It enforces a 15-minute minimum interval between runs.

3. **Stage 3 (API Server):** [`app.py`](app.py) serves the cached data via a FastAPI REST API to the Android app.

### Why This Approach? (and what was wrong before)

Garmin **rate-limits logins, not data reads.** The earlier design re-authenticated on every metric fetch (dozens of full SSO logins per run), which is what triggered the `429 Too Many Requests` errors.

A previous attempt tried to "fix" this with a headful Playwright/Chromium browser pointed at `connect.garmin.com/signin`. That made things worse: that endpoint is the human-facing web app and serves an interactive Cloudflare **"verify you are a human"** CAPTCHA, which scripts cannot reliably pass. There is no special "TLS fingerprinting WAF" blocking the library's auth endpoint — plain Python authenticates fine.

The correct, ban-safe pattern (and what this proxy now does): **log in once → dump OAuth tokens → reuse tokens for all reads.** Browser automation, Service Tickets, and Cloudflare cookies are no longer involved.

## Deployment

### 1. Install Dependencies

**Quick Setup (Recommended):**

```bash
cd garmin_proxy
./setup.sh
```

**Manual Setup:**

```bash
cd garmin_proxy
python3 -m venv venv
. venv/bin/activate
pip install -r requirements.txt
```

### 2. Configure Environment Variables

Set these environment variables:

```bash
export GARMIN_EMAIL=your.garmin.email@example.com
export GARMIN_PASSWORD=your_garmin_password
export ANDROID_PROXY_KEY=your_secure_secret_token_here
```

For persistent configuration, add them to `~/.bashrc` or use a `.env` file.

### 3. Initial Authentication (One-Time Setup)

Run the authentication bridge to generate your initial session:

```bash
. venv/bin/activate && python3 auth_bridge.py
```

This will:
- Log in to Garmin Connect once (prompts for an MFA code if 2FA is enabled)
- Save the OAuth tokens to `~/.garminconnect`

**IMPORTANT:** This script should only be run manually or when [`fetch_data.py`](fetch_data.py) reports that the tokens have expired. Never loop this script - repeated logins are what get rate-limited.

### 4. Fetch Initial Data

Run the data fetcher to populate the cache:

```bash
. venv/bin/activate && python3 fetch_data.py
```

This will:
- Resume from the saved OAuth tokens (no login)
- Fetch the last 7 days of health metrics
- Cache them in `garmin_cache.json`

### 5. Start the API Server

```bash
. venv/bin/activate && uvicorn app:app --host 0.0.0.0 --port 8000
```

For production with multiple workers:

```bash
. venv/bin/activate && uvicorn app:app --host 0.0.0.0 --port 8000 --workers 4
```

### 6. Automated Data Refresh (Systemd - Recommended)

Use systemd services for automatic background operation:

```bash
# 1. Create .env file with your credentials
cp .env.example .env
nano .env  # Edit with your actual credentials

# 2. Run initial authentication (one-time, requires display)
. venv/bin/activate && python3 auth_bridge.py

# 3. Install and enable systemd services
./install_autostart.sh
```

This will:
- Start the API server automatically on boot
- Fetch data every 30 minutes (respects 15-minute rate limit)
- Restart services automatically if they crash

**Manual Control:**

```bash
# Check status
systemctl --user status garmin-proxy.service
systemctl --user status garmin-fetch.timer

# View logs
journalctl --user -u garmin-proxy.service -f
journalctl --user -u garmin-fetch.service -f

# Stop/start services
systemctl --user stop garmin-proxy.service
systemctl --user start garmin-proxy.service
```

**Alternative: Cron Job**

If you prefer cron over systemd:

```bash
crontab -e
```

Add this line:

```cron
*/30 * * * * cd /home/twain/AndroidStudioProjects/tail/garmin_proxy && . venv/bin/activate && python3 fetch_data.py >> fetch.log 2>&1
```

### 7. Handling OAuth Token Expiration

If `fetch_data.py` fails with a 401 Unauthorized error, you need to re-authenticate:

```bash
. venv/bin/activate && python3 auth_bridge.py
. venv/bin/activate && python3 fetch_data.py
```

## API Endpoints

### GET `/api/v1/health-metrics`

Fetches health metrics for a specific date from the local cache.

**Headers:**
- `X-App-Auth`: Your secret token (must match `ANDROID_PROXY_KEY`)

**Query Parameters:**
- `date`: ISO date string (YYYY-MM-DD). Defaults to yesterday if not provided.

**Response:**
```json
{
  "date": "2026-06-16",
  "vo2_max": 45.0,
  "resting_hr": 52,
  "hrv_last_night": 68,
  "sleep_score": 85,
  "fitness_age": 28,
  "hrv_weekly_avg": 65,
  "steps": 10000,
  "altitude_ascent_meters": 50,
  "distance_meters": 5000,
  "calories": 2500,
  "active_minutes": 45,
  "floors_climbed": 10
}
```

**Error Responses:**
- `403`: Invalid or missing `X-App-Auth` header
- `404`: No data available for the requested date

### GET `/api/v1/health-metrics-range`

Fetches health metrics for a date range from the local cache.

**Headers:**
- `X-App-Auth`: Your secret token

**Query Parameters:**
- `start_date`: ISO date string (YYYY-MM-DD). Defaults to 7 days ago.
- `end_date`: ISO date string (YYYY-MM-DD). Defaults to yesterday.

**Response:**
```json
{
  "data": {
    "2026-06-15": { ... },
    "2026-06-16": { ... }
  },
  "metadata": {
    "start_date": "2026-06-15",
    "end_date": "2026-06-16",
    "count": 2
  }
}
```

### GET `/api/v1/cache-info`

Get information about the cached data.

**Headers:**
- `X-App-Auth`: Your secret token

**Response:**
```json
{
  "last_updated": "2026-06-21T07:30:00",
  "available_dates": ["2026-06-15", "2026-06-16", ...],
  "total_days": 7
}
```

### GET `/health`

Basic health check endpoint.

**Response:**
```json
{
  "status": "healthy",
  "version": "2.0.0"
}
```

### GET `/api/v1/health-check`

Comprehensive health check that validates proxy status and cache freshness.

**Headers:**
- `X-App-Auth`: Your secret token

**Response:**
```json
{
  "status": "healthy",
  "version": "2.0.0",
  "cache_file_exists": true,
  "cache_last_updated": "2026-06-21T07:30:00",
  "available_dates_count": 7,
  "has_recent_data": true
}
```

## Data Schema

| Field | Type | Description | Source |
|-------|------|-------------|--------|
| `vo2_max` | Double | Cardiovascular fitness score (ml/kg/min) | `get_training_status()` |
| `fitness_age` | Integer | Biological age based on fitness level | `get_my_fitness_age()` |
| `resting_hr` | Integer | Resting heart rate in BPM | `get_stats_and_body()` |
| `hrv_weekly_avg` | Integer | Average HRV over 7 days (ms) | `get_hrv_data()` |
| `hrv_last_night` | Integer | Last night's HRV (ms) | `get_sleep_data()` |
| `sleep_score` | Integer | Overall sleep quality (0-100) | `get_sleep_data()` |
| `steps` | Integer | Daily step count | `get_stats_and_body()` |
| `altitude_ascent_meters` | Double | Elevation gained in meters | `get_stats_and_body()` |
| `distance_meters` | Double | Distance traveled in meters | `get_stats_and_body()` |
| `calories` | Integer | Calories burned | `get_stats_and_body()` |
| `active_minutes` | Integer | Active minutes | `get_stats_and_body()` |
| `floors_climbed` | Integer | Floors climbed | `get_stats_and_body()` |

## Rate Limiting & Safety Rules

To avoid getting your Garmin account banned, the system enforces these rules:

1. **Never loop the auth script:** [`auth_bridge.py`](auth_bridge.py) should only be executed manually or *only* when [`fetch_data.py`](fetch_data.py) reports the saved tokens have expired. Repeated logins are what get rate-limited.

2. **Polling Frequency:** The API should not be polled more than once every 15 minutes. This is enforced in [`fetch_data.py`](fetch_data.py).

3. **Local Caching:** All JSON responses from Garmin are written directly to [`garmin_cache.json`](garmin_cache.json). The application layer queries the local datastore, not the Garmin API directly.

4. **OAuth Token Persistence:** OAuth tokens are saved to `~/.garminconnect` and reused for every subsequent fetch, so a normal run performs **zero** logins.

## Security

- The proxy is protected by a simple API key via the `X-App-Auth` header
- Garmin credentials are stored as environment variables, never in code
- OAuth tokens are stored in `~/.garminconnect` (user home directory)
- All requests are logged for debugging purposes

## Troubleshooting

### "No saved Garmin tokens... Run auth_bridge.py first."

Run the authentication bridge once to generate tokens:

```bash
. venv/bin/activate && python3 auth_bridge.py
```

### "Authentication failed" during auth_bridge.py

- Verify your Garmin email and password are correct
- If your account uses MFA/2FA, enter the emailed/app code when prompted
- Check that you have a stable internet connection

### auth_bridge.py logs "429 — IP rate limited" / "Portal login: waiting Ns"

This is expected when your IP is temporarily flagged from earlier over-fetching.
The installed `garminconnect` is a custom fork with a 5-strategy login chain; the
fast mobile/widget strategies may get 429'd, after which it automatically falls
back to the `portal` strategy with Cloudflare-aware backoff (the "waiting Ns"
messages). **Let it run** - it usually succeeds within a minute or two and writes
the tokens.

If every strategy 429s, the script waits once (`GARMIN_AUTH_BACKOFF`, default 300s)
and retries a single time, then exits with code 3. In that case the IP block is
still active: wait ~30-60 minutes (or switch network / VPN) and run it again.
**Do not loop the script** - repeated hammering extends the block.

Once tokens are saved, [`fetch_data.py`](fetch_data.py) performs **zero** logins,
so it is never affected by this.

### "Rate limit active. Next fetch allowed in X seconds."

Wait for the rate limit to expire (minimum 15 minutes between fetches).

### "No data available for the requested date"

- This is normal for future dates or dates before you started using Garmin
- Check that your Garmin device is syncing properly with Garmin Connect
- Verify the cache has been populated: `. venv/bin/activate && python3 fetch_data.py`

### "Forbidden: Invalid App Token"

- Ensure `ANDROID_PROXY_KEY` is set in your environment
- Verify the token in your Android app settings matches exactly

### Tokens Expired (fetch_data.py exits with "Re-run auth_bridge.py")

The data fetcher never logs in on its own. When the saved tokens expire, re-run the one-time auth step:

```bash
. venv/bin/activate && python3 auth_bridge.py
```

No display server, Playwright, or `xvfb` is required - authentication is a plain HTTP login.

## File Structure

```
garmin_proxy/
├── app.py                  # FastAPI server (serves cached data)
├── auth_bridge.py          # One-time token generator (logs in, saves ~/.garminconnect)
├── fetch_data.py           # Data fetcher with caching and rate limiting
├── requirements.txt        # Python dependencies
├── setup.sh                # Quick setup script
├── README.md              # This file
├── venv/                  # Python virtual environment (created by setup)
├── garmin_cache.json      # Generated by fetch_data.py (7-day metrics cache)
└── .last_fetch_timestamp  # Generated by fetch_data.py (rate limiting)
```

## Migration from v1.x

If you're upgrading from the old version (v1.x):

1. Install new dependencies:
   ```bash
   . venv/bin/activate && pip install -r requirements.txt
   playwright install chromium
   ```

2. Run the new authentication bridge:
   ```bash
   . venv/bin/activate && python3 auth_bridge.py
   ```

3. Fetch initial data:
   ```bash
   . venv/bin/activate && python3 fetch_data.py
   ```

4. Update your cron job to use `fetch_data.py` instead of direct API calls.

5. The API endpoints remain compatible with v1.x, so no changes are needed on the Android app side.

## License

MIT License