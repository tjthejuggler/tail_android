import os
import json
import logging
from datetime import datetime
from pathlib import Path
from fastapi import FastAPI, HTTPException, Security
from fastapi.security import APIKeyHeader
from typing import Dict, Optional

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Garmin Data Bridge (Cached)", version="2.0.0")

# Protect your endpoint so only your Android app can query it
API_KEY = os.getenv("ANDROID_PROXY_KEY")
api_key_header = APIKeyHeader(name="X-App-Auth")

# Cache file location (same as used by garmin_fetcher.py)
CACHE_FILE = Path("garmin_cache.json")

# All metrics we track
METRICS = [
    "vo2_max",
    "resting_hr",
    "hrv_last_night",
    "sleep_score",
    "fitness_age",
    "hrv_weekly_avg",
    "steps",
    "altitude_ascent_meters",
    "distance_meters",
    "calories",
    "active_minutes",
    "floors_climbed"
]


def load_cache() -> Dict:
    """Load cached data from file."""
    if CACHE_FILE.exists():
        try:
            with open(CACHE_FILE, 'r') as f:
                return json.load(f)
        except Exception as e:
            logger.warning(f"Failed to load cache file: {e}")
    
    # Return empty cache structure
    return {metric: {} for metric in METRICS}


def get_metrics_for_date(target_date: str) -> Dict:
    """Get all available metrics for a specific date from cache."""
    cache = load_cache()
    
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
    
    for metric in METRICS:
        if metric in cache and target_date in cache[metric]:
            metrics[metric] = cache[metric][target_date]
    
    return metrics


def get_all_cached_dates() -> list:
    """Get list of all dates that have any cached data."""
    cache = load_cache()
    dates = set()
    
    for metric_data in cache.values():
        if isinstance(metric_data, dict):
            dates.update(metric_data.keys())
    
    return sorted(dates, reverse=True)


def get_available_metrics_for_date(target_date: str) -> list:
    """Get list of metrics that have data for a specific date."""
    cache = load_cache()
    available = []
    
    for metric in METRICS:
        if metric in cache and target_date in cache[metric]:
            available.append(metric)
    
    return available


@app.get("/api/v1/health-metrics")
def get_health_metrics(date: str = None, api_key: str = Security(api_key_header)):
    """
    Get health metrics for a specific date from the cache.
    
    This endpoint serves data that has been pre-fetched by garmin_fetcher.py
    and cached locally. It does not make any direct Garmin API calls.
    
    Headers:
    - X-App-Auth: Your secret token (must match ANDROID_PROXY_KEY)
    
    Query Parameters:
    - date: ISO date string (YYYY-MM-DD). Defaults to today if not provided.
    
    Response:
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
    
    Error Responses:
    - 403: Invalid or missing X-App-Auth header
    """
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    
    # Default to today if no date supplied
    if date is None:
        target_date = datetime.now().date().isoformat()
    else:
        target_date = date
    
    # Get metrics from cache
    metrics = get_metrics_for_date(target_date)
    
    # Log what we're returning
    available = [k for k, v in metrics.items() if v is not None and k != "date"]
    if available:
        logger.info(f"Serving cached metrics for {target_date}: {available}")
    else:
        logger.warning(f"No cached data available for {target_date}")
    
    return metrics


@app.get("/api/v1/health-metrics/batch")
def get_health_metrics_batch(
    start_date: str,
    end_date: str,
    api_key: str = Security(api_key_header)
):
    """
    Get health metrics for a range of dates from the cache.
    
    Headers:
    - X-App-Auth: Your secret token (must match ANDROID_PROXY_KEY)
    
    Query Parameters:
    - start_date: ISO date string (YYYY-MM-DD) - inclusive
    - end_date: ISO date string (YYYY-MM-DD) - inclusive
    
    Response:
    {
      "2026-06-15": { "vo2_max": 45, ... },
      "2026-06-16": { "vo2_max": 46, ... },
      ...
    }
    
    Error Responses:
    - 403: Invalid or missing X-App-Auth header
    - 400: Invalid date range
    """
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    
    try:
        start = datetime.strptime(start_date, "%Y-%m-%d").date()
        end = datetime.strptime(end_date, "%Y-%m-%d").date()
    except ValueError:
        raise HTTPException(status_code=400, detail="Invalid date format. Use YYYY-MM-DD")
    
    if start > end:
        raise HTTPException(status_code=400, detail="start_date must be before or equal to end_date")
    
    # Limit range to prevent excessive responses
    if (end - start).days > 30:
        raise HTTPException(status_code=400, detail="Date range cannot exceed 30 days")
    
    results = {}
    current = start
    while current <= end:
        date_str = current.isoformat()
        metrics = get_metrics_for_date(date_str)
        # Only include dates with at least one metric
        if any(v is not None for k, v in metrics.items() if k != "date"):
            results[date_str] = metrics
        current += timedelta(days=1)
    
    logger.info(f"Serving batch metrics for {len(results)} dates from {start_date} to {end_date}")
    return results


@app.get("/api/v1/available-dates")
def get_available_dates(
    limit: int = 30,
    api_key: str = Security(api_key_header)
):
    """
    Get list of dates that have cached data available.
    
    Headers:
    - X-App-Auth: Your secret token (must match ANDROID_PROXY_KEY)
    
    Query Parameters:
    - limit: Maximum number of dates to return (default: 30)
    
    Response:
    {
      "dates": ["2026-06-20", "2026-06-19", "2026-06-18", ...]
    }
    
    Error Responses:
    - 403: Invalid or missing X-App-Auth header
    """
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    
    dates = get_all_cached_dates()
    limited_dates = dates[:limit] if limit > 0 else dates
    
    return {"dates": limited_dates}


@app.get("/api/v1/metrics-for-date/{date}")
def get_metrics_summary(
    date: str,
    api_key: str = Security(api_key_header)
):
    """
    Get a summary of which metrics are available for a specific date.
    
    Headers:
    - X-App-Auth: Your secret token (must match ANDROID_PROXY_KEY)
    
    Path Parameters:
    - date: ISO date string (YYYY-MM-DD)
    
    Response:
    {
      "date": "2026-06-20",
      "available_metrics": ["vo2_max", "resting_hr", "steps"],
      "missing_metrics": ["hrv_last_night", "sleep_score", ...]
    }
    
    Error Responses:
    - 403: Invalid or missing X-App-Auth header
    """
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    
    available = get_available_metrics_for_date(date)
    missing = [m for m in METRICS if m not in available]
    
    return {
        "date": date,
        "available_metrics": available,
        "missing_metrics": missing
    }


@app.get("/health")
def health_check():
    """Simple health check endpoint."""
    return {"status": "healthy"}


@app.get("/api/v1/health-check")
def comprehensive_health_check(api_key: str = Security(api_key_header)):
    """
    Comprehensive health check that validates:
    1. Proxy is running
    2. App token is valid
    3. Cache file exists and is readable
    4. Cache has some data
    """
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    
    cache = load_cache()
    dates = get_all_cached_dates()
    
    return {
        "status": "healthy",
        "cache_file_exists": CACHE_FILE.exists(),
        "cache_readable": len(cache) > 0,
        "cached_dates_count": len(dates),
        "oldest_date": dates[-1] if dates else None,
        "newest_date": dates[0] if dates else None,
        "metrics_tracked": len(METRICS)
    }


# Import timedelta for date range calculations
from datetime import timedelta