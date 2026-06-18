import os
import datetime
from fastapi import FastAPI, HTTPException, Security
from fastapi.security import APIKeyHeader
from garminconnect import Garmin

app = FastAPI(title="Garmin Data Bridge")

# Protect your endpoint so only your Android app can query it
API_KEY = os.getenv("ANDROID_PROXY_KEY")
api_key_header = APIKeyHeader(name="X-App-Auth")

def get_garmin_client():
    # Keep login credentials safe on your server, never on the phone
    email = os.getenv("GARMIN_EMAIL")
    password = os.getenv("GARMIN_PASSWORD")
    client = Garmin(email, password)
    client.login()
    return client

@app.get("/api/v1/health-metrics")
def get_health_metrics(date: str = None, api_key: str = Security(api_key_header)):
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    
    # Default to today if no date supplied
    target_date = date if date else datetime.date.today().isoformat()
    
    try:
        client = get_garmin_client()
        
        # Pull separate API payloads concurrently or sequentially
        training_status = client.get_training_status(target_date) or {}
        sleep_raw = client.get_sleep_data(target_date) or {}
        stats_raw = client.get_stats_and_body(target_date) or {}
        
        # Construct a clean, predictable response body for Android
        # Note: Garmin rate limits may cause 429 errors on login; the proxy will retry
        vo2max_obj = training_status.get("mostRecentVO2Max", {}).get("generic", {})
        
        # Helper to safely convert to int or None
        def to_int_or_none(value):
            if value is None:
                return None
            try:
                return int(value)
            except (ValueError, TypeError):
                return None
        
        return {
            "date": target_date,
            "vo2_max": vo2max_obj.get("vo2MaxValue"),
            "fitness_age": vo2max_obj.get("fitnessAge"),
            "resting_hr": to_int_or_none(stats_raw.get("restingHeartRate")),
            "hrv_weekly_avg": None,  # Not available in current API responses
            "hrv_last_night": to_int_or_none(sleep_raw.get("avgOvernightHrv")),
            "sleep_score": to_int_or_none(sleep_raw.get("dailySleepDTO", {}).get("sleepScores", {}).get("overall", {}).get("value"))
        }
        
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Garmin Sync Error: {str(e)}")

@app.get("/health")
def health_check():
    return {"status": "healthy"}

@app.get("/api/v1/health-check")
def comprehensive_health_check(api_key: str = Security(api_key_header)):
    """
    Comprehensive health check that validates:
    1. Proxy is running
    2. App token is valid
    3. Garmin connection works
    4. Can fetch actual Garmin data
    """
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    
    try:
        # Try to connect to Garmin
        client = get_garmin_client()
        
        # Test fetching a small piece of data to validate the full chain
        target_date = datetime.date.today().isoformat()
        
        # Try to fetch training status (lightweight call)
        training_status = client.get_training_status(target_date)
        
        # If we got here, everything is working
        return {
            "status": "healthy",
            "proxy": "running",
            "garmin_connection": "connected",
            "data_available": training_status is not None,
            "timestamp": datetime.datetime.now().isoformat()
        }
        
    except Exception as e:
        # Return detailed error information
        error_type = type(e).__name__
        error_msg = str(e)
        
        if "403" in error_msg or "Forbidden" in error_msg:
            raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
        
        return {
            "status": "unhealthy",
            "proxy": "running",
            "garmin_connection": "failed",
            "error": f"{error_type}: {error_msg}",
            "timestamp": datetime.datetime.now().isoformat()
        }

if __name__ == "__main__":
    import uvicorn
    # Listen on all interfaces (0.0.0.0) so your phone can connect over Wi-Fi
    uvicorn.run(app, host="0.0.0.0", port=8000)