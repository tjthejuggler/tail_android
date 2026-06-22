"""
Garmin Data Bridge API - FastAPI Server

This server serves cached Garmin health metrics to the Tail Android app.
It reads from garmin_cache.json which is populated by fetch_data.py.

The two-stage architecture:
1. auth_bridge.py - Uses Playwright to bypass Cloudflare WAF and get OAuth tokens
2. fetch_data.py - Uses OAuth tokens to fetch data and cache it locally
3. app.py - Serves cached data to the Android app

This approach avoids 429 rate limiting by:
- Using a real browser for authentication (bypasses TLS fingerprinting)
- Caching data locally (minimizes API calls)
- Enforcing 15-minute minimum intervals between fetches
"""

import os
import json
import logging
import subprocess
import sys
from datetime import datetime, timedelta
from typing import Dict, Any, Optional
from fastapi import FastAPI, HTTPException, Security, BackgroundTasks
from fastapi.security import APIKeyHeader
from fastapi.responses import JSONResponse

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(title="Garmin Data Bridge", version="2.0.0")

# Protect your endpoint so only your Android app can query it
API_KEY = os.getenv("ANDROID_PROXY_KEY")
api_key_header = APIKeyHeader(name="X-App-Auth")

# Cache file path
CACHE_FILE = "garmin_cache.json"


def load_cache() -> Dict[str, Any]:
    """Load the cached Garmin data from disk."""
    try:
        if os.path.exists(CACHE_FILE):
            with open(CACHE_FILE, "r") as f:
                return json.load(f)
    except Exception as e:
        logger.error(f"Error loading cache: {e}")
    return {"data": {}, "metadata": {}}


def get_metrics_for_date(date: str) -> Dict[str, Any]:
    """
    Get metrics for a specific date from cache.
    Returns None if date not found in cache.
    """
    cache = load_cache()
    return cache.get("data", {}).get(date)


def get_available_dates() -> list:
    """Get list of dates available in cache."""
    cache = load_cache()
    return list(cache.get("data", {}).keys())


@app.get("/api/v1/health-metrics")
def get_health_metrics(date: str = None, api_key: str = Security(api_key_header)):
    """
    Primary ingestion endpoint for the Android Tail Application.
    Returns cached health metrics for a specific date.
    
    Query Parameters:
    - date: ISO date string (YYYY-MM-DD). Defaults to yesterday if not provided.
    
    Response:
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
    """
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    
    # Default to yesterday if no date supplied (today's data is incomplete)
    if date is None:
        target_date = (datetime.now().date() - timedelta(days=1)).isoformat()
    else:
        target_date = date
    
    metrics = get_metrics_for_date(target_date)
    
    if metrics is None:
        available_dates = get_available_dates()
        raise HTTPException(
            status_code=404,
            detail=f"No data available for {target_date}. Available dates: {available_dates}"
        )
    
    return metrics


@app.get("/api/v1/health-metrics-range")
def get_health_metrics_range(
    start_date: str = None,
    end_date: str = None,
    api_key: str = Security(api_key_header)
):
    """
    Get health metrics for a range of dates from cache.
    
    Query Parameters:
    - start_date: ISO date string (YYYY-MM-DD). Defaults to 7 days ago.
    - end_date: ISO date string (YYYY-MM-DD). Defaults to yesterday.
    
    Response:
    {
        "data": {
            "2026-06-15": { ... },
            "2026-06-16": { ... },
            ...
        },
        "metadata": {
            "start_date": "2026-06-15",
            "end_date": "2026-06-16",
            "count": 2
        }
    }
    """
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    
    # Default to last 7 days if no dates supplied
    if end_date is None:
        end_date = (datetime.now().date() - timedelta(days=1)).isoformat()
    if start_date is None:
        start_date = (datetime.now().date() - timedelta(days=7)).isoformat()
    
    cache = load_cache()
    cached_data = cache.get("data", {})
    
    # Filter data within the date range
    result = {}
    for date_str, metrics in cached_data.items():
        if start_date <= date_str <= end_date:
            result[date_str] = metrics
    
    return {
        "data": result,
        "metadata": {
            "start_date": start_date,
            "end_date": end_date,
            "count": len(result)
        }
    }


@app.get("/api/v1/cache-info")
def get_cache_info(api_key: str = Security(api_key_header)):
    """
    Get information about the cached data.
    
    Response:
    {
        "last_updated": "2026-06-21T07:30:00",
        "available_dates": ["2026-06-15", "2026-06-16", ...],
        "total_days": 7
    }
    """
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    
    cache = load_cache()
    available_dates = sorted(cache.get("data", {}).keys(), reverse=True)
    
    return {
        "last_updated": cache.get("metadata", {}).get("last_updated"),
        "available_dates": available_dates,
        "total_days": len(available_dates)
    }


@app.get("/health")
def health_check():
    """Basic health check endpoint."""
    return {"status": "healthy", "version": "2.0.0"}


@app.get("/api/v1/health-check")
def comprehensive_health_check(api_key: str = Security(api_key_header)):
    """
    Comprehensive health check that validates:
    1. Proxy is running
    2. App token is valid
    3. Cache file exists and is readable
    4. Cache has recent data
    """
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    
    cache = load_cache()
    available_dates = get_available_dates()
    
    # Check if cache has recent data (within last 2 days)
    has_recent_data = False
    if available_dates:
        latest_date = max(available_dates)
        latest = datetime.fromisoformat(latest_date)
        two_days_ago = datetime.now().date() - timedelta(days=2)
        has_recent_data = latest.date() >= two_days_ago
    
    return {
        "status": "healthy",
        "version": "2.0.0",
        # Field names the Android app's performHealthCheck() parses:
        #   proxy == "running", garmin_connection == "connected", data_available
        # (see GarminService.kt). The proxy serves cached data, so as long as
        # the cache has dates the chain is considered connected + available.
        "proxy": "running",
        "garmin_connection": "connected" if available_dates else "no_data",
        "data_available": len(available_dates) > 0,
        "cache_file_exists": os.path.exists(CACHE_FILE),
        "cache_last_updated": cache.get("metadata", {}).get("last_updated"),
        "available_dates_count": len(available_dates),
        "has_recent_data": has_recent_data
    }


def _run_fetch_in_background(days: int = 7, force: bool = False):
    """Run fetch_data.py in the background."""
    script_dir = os.path.dirname(os.path.abspath(__file__))
    script_path = os.path.join(script_dir, "fetch_data.py")
    # Use the same Python interpreter that's running this app
    python_exe = sys.executable
    cmd = [python_exe, script_path, "--days", str(days)]
    if force:
        cmd.append("--force")
    try:
        subprocess.run(
            cmd,
            cwd=script_dir,
            capture_output=True,
            timeout=300,  # 5 minute timeout
        )
    except subprocess.TimeoutExpired:
        logger.error("Fetch operation timed out")
    except Exception as e:
        logger.error(f"Fetch operation failed: {e}")


@app.post("/api/v1/force-fetch")
def trigger_force_fetch(
    background_tasks: BackgroundTasks,
    days: int = 7,
    api_key: str = Security(api_key_header)
):
    """
    Trigger a forced fetch of Garmin data, bypassing rate limiting.
    
    Query Parameters:
    - days: Number of days to fetch (default: 7)
    
    Response:
    {
        "status": "fetch_started",
        "message": "Forced fetch started in background"
    }
    """
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    
    background_tasks.add_task(_run_fetch_in_background, days=days, force=True)
    
    logger.info(f"Forced fetch triggered for {days} days")
    return {
        "status": "fetch_started",
        "message": f"Forced fetch started in background for {days} days"
    }