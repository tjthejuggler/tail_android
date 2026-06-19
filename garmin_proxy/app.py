import os
import datetime
import logging
from fastapi import FastAPI, HTTPException, Security
from fastapi.security import APIKeyHeader
from garminconnect import Garmin

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Garmin Data Bridge", version="1.1.0")

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

def fetch_fitness_age(client):
    """
    Fetches the Improved Fitness Age from Garmin's dedicated microservice.
    Returns None if the user's device doesn't support this metric or if the API fails.
    """
    try:
        fitness_data = client.get_my_fitness_age()
        if isinstance(fitness_data, dict):
            return fitness_data.get("fitnessAge")
        logger.warning("Fitness age payload received but was not a valid dictionary structure.")
        return None
    except Exception as e:
        logger.warning(f"Network or parsing failure while fetching fitness age data: {e}")
        return None

def fetch_hrv_weekly_average(client):
    """
    Fetches the HRV 7-day rolling average from Garmin's HRV service.
    Implements a manual fallback calculation if the native metric is missing.
    """
    try:
        # Use UTC date to prevent timezone boundary issues
        today_iso = datetime.datetime.now(datetime.timezone.utc).date().isoformat()
        
        hrv_payload = client.get_hrv_data(today_iso)
        
        if not hrv_payload or not isinstance(hrv_payload, dict):
            logger.info(f"No HRV payload returned for date {today_iso}. User may not wear device to sleep.")
            return None
            
        # Traverse the JSON hierarchy to find the weekly average
        hrv_summary = hrv_payload.get("hrvSummary", {})
        
        # Check both possible key names (regional variations)
        weekly_avg = hrv_summary.get("weeklyAvg") or hrv_summary.get("7DayAvg")
        
        # Fallback: manually compute from baseline history if native metric is missing
        if weekly_avg is None:
            baseline_node = hrv_payload.get("baseline", {})
            baseline_history = baseline_node.get("baselineHistory", [])
            
            if baseline_history and len(baseline_history) > 0:
                # Extract lastNightAvg values from the trailing 7 days
                recent_values = [
                    entry.get("lastNightAvg")
                    for entry in baseline_history[-7:]
                    if isinstance(entry, dict) and entry.get("lastNightAvg") is not None
                ]
                
                if recent_values:
                    weekly_avg = sum(recent_values) // len(recent_values)
                    logger.info("HRV Weekly Average manually computed from baseline history array.")
                    
        return weekly_avg
        
    except Exception as e:
        logger.warning(f"Failed to fetch or compute HRV weekly average: {e}")
        return None

@app.get("/api/v1/health-metrics")
def get_health_metrics(date: str = None, api_key: str = Security(api_key_header)):
    """
    Primary ingestion endpoint for the Android Tail Application.
    Aggregates data across five distinct Garmin microservices into a single DTO.
    """
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    
    # Default to yesterday if no date supplied (today's data is incomplete)
    if date is None:
        target_date = (datetime.date.today() - datetime.timedelta(days=1)).isoformat()
    else:
        target_date = date
    
    # Establish the strict return dictionary contract with default None values
    metrics = {
        "date": target_date,
        "vo2_max": None,
        "resting_hr": None,
        "hrv_last_night": None,
        "sleep_score": None,
        "fitness_age": None,
        "hrv_weekly_avg": None,
        "steps": None,
        "altitude_ascent_meters": None,
        "distance_meters": None,
        "calories": None,
        "active_minutes": None,
        "floors_climbed": None
    }
    
    try:
        client = get_garmin_client()
        
        # --- Stage 1: Fetching Baseline Legacy Metrics ---
        
        # Training Status (Extracts VO2 Max)
        training_status = client.get_training_status(target_date)
        if isinstance(training_status, dict):
            vo2_node = training_status.get("mostRecentVO2Max")
            if isinstance(vo2_node, dict):
                vo2_generic = vo2_node.get("generic")
                if isinstance(vo2_generic, dict):
                    metrics["vo2_max"] = vo2_generic.get("vo2MaxValue")

        # Daily Sleep Data (Extracts HRV Last Night & Sleep Score)
        sleep_data = client.get_sleep_data(target_date)
        if isinstance(sleep_data, dict):
            metrics["hrv_last_night"] = sleep_data.get("avgOvernightHrv")
            # Deep traversal requires safe .get() chaining
            sleep_dto = sleep_data.get("dailySleepDTO")
            if isinstance(sleep_dto, dict):
                sleep_scores = sleep_dto.get("sleepScores")
                if isinstance(sleep_scores, dict):
                    overall = sleep_scores.get("overall")
                    if isinstance(overall, dict):
                        metrics["sleep_score"] = overall.get("value")

        # Stats and Body Configuration (Extracts Resting HR, Steps, Altitude, etc.)
        stats = client.get_stats_and_body(target_date)
        if isinstance(stats, dict):
            metrics["resting_hr"] = stats.get("restingHeartRate")
            metrics["steps"] = stats.get("steps")
            metrics["altitude_ascent_meters"] = stats.get("elevationGain")
            metrics["distance_meters"] = stats.get("distance")
            metrics["calories"] = stats.get("calories")
            metrics["active_minutes"] = stats.get("activeMinutes") or stats.get("moderateIntensityMinutes")
            metrics["floors_climbed"] = stats.get("floorsClimbed")

        # --- Stage 2: Fetching Newly Operationalized Metrics ---
        
        # Improved Fitness Age
        metrics["fitness_age"] = fetch_fitness_age(client)

        # HRV 7-Day Rolling Average
        hrv_weekly = fetch_hrv_weekly_average(client)
        if hrv_weekly is not None:
            metrics["hrv_weekly_avg"] = int(hrv_weekly)
        
        # Helper to safely convert to int or None for legacy metrics
        def to_int_or_none(value):
            if value is None:
                return None
            try:
                return int(value)
            except (ValueError, TypeError):
                return None
        
        # Apply int conversion where needed
        if metrics["resting_hr"] is not None:
            metrics["resting_hr"] = to_int_or_none(metrics["resting_hr"])
        if metrics["hrv_last_night"] is not None:
            metrics["hrv_last_night"] = to_int_or_none(metrics["hrv_last_night"])
        if metrics["sleep_score"] is not None:
            metrics["sleep_score"] = to_int_or_none(metrics["sleep_score"])
        
        return metrics
        
    except Exception as e:
        logger.exception(f"Unexpected terminal error processing Garmin payloads: {e}")
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