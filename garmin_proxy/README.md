# Garmin Proxy for Tail Android App

A secure Python proxy that bridges Garmin Connect data to the Tail Android habit tracker app.

## Architecture

This proxy is necessary because Garmin doesn't sync advanced metrics (VO2 Max, Fitness Age, HRV, etc.) to Android's local storage. The proxy authenticates with Garmin's cloud using the `python-garminconnect` library and exposes a simple REST API for the Android app.

## Deployment

### 1. Deploy to a Serverless Platform

Recommended platforms:
- **Render** (free tier available)
- **Railway**
- **Fly.io**
- **Any VPS** with Python 3.10+

### 2. Configure Environment Variables

Set these environment variables in your hosting platform's dashboard:

```
GARMIN_EMAIL=your.garmin.email@example.com
GARMIN_PASSWORD=your_garmin_password
ANDROID_PROXY_KEY=your_secure_secret_token_here
```

### 3. Install Dependencies

```bash
pip install -r requirements.txt
```

### 4. Run the Server

```bash
uvicorn app:app --host 0.0.0.0 --port 8000
```

For production, use:
```bash
uvicorn app:app --host 0.0.0.0 --port 8000 --workers 4
```

## API Endpoints

### GET `/api/v1/health-metrics`

Fetches health metrics for a specific date.

**Headers:**
- `X-App-Auth`: Your secret token (must match `ANDROID_PROXY_KEY`)

**Query Parameters:**
- `date`: ISO date string (YYYY-MM-DD). Defaults to today if not provided.

**Response:**
```json
{
  "date": "2026-06-16",
  "vo2_max": 45.0,
  "fitness_age": 28,
  "resting_hr": 52,
  "hrv_weekly_avg": 65,
  "hrv_last_night": 68,
  "sleep_score": 85
}
```

**Error Responses:**
- `403`: Invalid or missing `X-App-Auth` header
- `404`: No data available for the requested date
- `500`: Garmin API error (includes error message)

### GET `/health`

Health check endpoint.

**Response:**
```json
{
  "status": "healthy"
}
```

## Data Schema

| Field | Type | Description | Source |
|-------|------|-------------|--------|
| `vo2_max` | Double | Cardiovascular fitness score (ml/kg/min) | `get_training_status()` |
| `fitness_age` | Integer | Biological age based on fitness level | `get_my_fitness_age()` |
| `resting_hr` | Integer | Resting heart rate in BPM | `get_training_status()` |
| `hrv_weekly_avg` | Integer | Average HRV over 7 days (ms) | `get_hrv_data()` |
| `hrv_last_night` | Integer | Last night's HRV (ms) | `get_hrv_data()` |
| `sleep_score` | Integer | Overall sleep quality (0-100) | `get_sleep_data()` |

## Security

- The proxy is protected by a simple API key via the `X-App-Auth` header
- Garmin credentials are stored as environment variables on the server, never on the phone
- All requests are logged for debugging purposes

## Troubleshooting

### "Garmin Sync Error: Invalid credentials"
- Verify your Garmin email and password are correct
- Garmin may require re-authentication if you've changed your password recently

### "Forbidden: Invalid App Token"
- Ensure `ANDROID_PROXY_KEY` is set on the server
- Verify the token in your Android app settings matches exactly

### "No data available for the requested date"
- This is normal for future dates or dates before you started using Garmin
- Check that your Garmin device is syncing properly with Garmin Connect

## License

MIT License - See LICENSE file for details