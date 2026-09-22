#!/usr/bin/env python3
"""
Rate-Limit Aware Garmin Data Fetcher

Continuously fetches Garmin health data while respecting API rate limits.
Maintains persistent state to track which data points have been fetched
and implements exponential backoff for rate limit errors.

Architecture:
- Fetches one metric per date at a time to avoid rate limits
- Prioritizes: 7 days back → current day → continuous updates
- Tracks completed fetches in persistent state
- Implements exponential backoff for rate limit errors
- Builds up data cache for phone app to consume
"""

import os
import json
import time
import logging
from datetime import datetime, date, timedelta
from typing import Dict, Optional, Set, Tuple
from pathlib import Path
from garminconnect import Garmin

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# Configuration
STATE_FILE = Path("garmin_fetcher_state.json")
CACHE_FILE = Path("garmin_cache.json")
HISTORICAL_DAYS = 7  # How many days back to prioritize
BASE_RETRY_DELAY = 60  # Initial retry delay in seconds
MAX_RETRY_DELAY = 3600  # Maximum retry delay (1 hour)
CONTINUOUS_FETCH_INTERVAL = 300  # 5 minutes between current-day updates

# All metrics we want to fetch
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


class GarminFetcher:
    """Rate-limit aware Garmin data fetcher with persistent state."""
    
    def __init__(self, email: str, password: str):
        self.email = email
        self.password = password
        self.client: Optional[Garmin] = None
        self.state = self._load_state()
        self.cache = self._load_cache()
        self.rate_limit_errors: int = 0
        self.last_rate_limit_time: Optional[float] = None
        self.current_retry_delay = BASE_RETRY_DELAY
        
    def _load_state(self) -> Dict:
        """Load persistent state from file."""
        if STATE_FILE.exists():
            try:
                with open(STATE_FILE, 'r') as f:
                    return json.load(f)
            except Exception as e:
                logger.warning(f"Failed to load state file: {e}")
        
        return {
            "completed_fetches": {},  # {metric: {date: timestamp}}
            "last_full_fetch_date": None,
            "current_day_cycle_complete": False,
            "last_continuous_fetch": None
        }
    
    def _save_state(self):
        """Save persistent state to file."""
        try:
            with open(STATE_FILE, 'w') as f:
                json.dump(self.state, f, indent=2)
        except Exception as e:
            logger.error(f"Failed to save state file: {e}")
    
    def _load_cache(self) -> Dict:
        """Load cached data from file."""
        if CACHE_FILE.exists():
            try:
                with open(CACHE_FILE, 'r') as f:
                    return json.load(f)
            except Exception as e:
                logger.warning(f"Failed to load cache file: {e}")
        
        return {metric: {} for metric in METRICS}
    
    def _save_cache(self):
        """Save cached data to file."""
        try:
            with open(CACHE_FILE, 'w') as f:
                json.dump(self.cache, f, indent=2)
        except Exception as e:
            logger.error(f"Failed to save cache file: {e}")
    
    def _connect(self) -> bool:
        """Connect to Garmin Connect."""
        try:
            if self.client is None:
                self.client = Garmin(self.email, self.password)
            self.client.login()
            logger.info("Successfully connected to Garmin Connect")
            return True
        except Exception as e:
            logger.error(f"Failed to connect to Garmin Connect: {e}")
            return False
    
    def _handle_rate_limit(self):
        """Handle rate limit error with exponential backoff."""
        self.rate_limit_errors += 1
        self.last_rate_limit_time = time.time()
        
        # Exponential backoff
        delay = min(
            BASE_RETRY_DELAY * (2 ** (self.rate_limit_errors - 1)),
            MAX_RETRY_DELAY
        )
        self.current_retry_delay = delay
        
        logger.warning(
            f"Rate limit error #{self.rate_limit_errors}. "
            f"Waiting {delay}s before retry..."
        )
        time.sleep(delay)
    
    def _reset_rate_limit_tracking(self):
        """Reset rate limit tracking after successful request."""
        if self.rate_limit_errors > 0:
            logger.info(
                f"Request successful after {self.rate_limit_errors} rate limit errors. "
                "Resetting backoff."
            )
        self.rate_limit_errors = 0
        self.last_rate_limit_time = None
        self.current_retry_delay = BASE_RETRY_DELAY
    
    def _fetch_single_metric(
        self, metric: str, target_date: str
    ) -> Optional[Dict]:
        """Fetch a single metric for a specific date."""
        try:
            # Reconnect if needed
            if not self._connect():
                return None
            
            # Fetch data using Garmin Connect API
            if metric == "vo2_max":
                data = self.client.get_training_status(target_date)
                if data and isinstance(data, dict):
                    vo2_node = data.get("mostRecentVO2Max")
                    if vo2_node and isinstance(vo2_node, dict):
                        vo2_generic = vo2_node.get("generic")
                        if vo2_generic and isinstance(vo2_generic, dict):
                            value = vo2_generic.get("vo2MaxValue")
                            if value is not None:
                                return {"date": target_date, "vo2_max": value}
            
            elif metric == "resting_hr":
                stats = self.client.get_stats_and_body(target_date)
                if stats and isinstance(stats, dict):
                    value = stats.get("restingHeartRate")
                    if value is not None:
                        return {"date": target_date, "resting_hr": int(value)}
            
            elif metric == "hrv_last_night":
                sleep_data = self.client.get_sleep_data(target_date)
                if sleep_data and isinstance(sleep_data, dict):
                    value = sleep_data.get("avgOvernightHrv")
                    if value is not None:
                        return {"date": target_date, "hrv_last_night": int(value)}
            
            elif metric == "sleep_score":
                sleep_data = self.client.get_sleep_data(target_date)
                if sleep_data and isinstance(sleep_data, dict):
                    sleep_dto = sleep_data.get("dailySleepDTO")
                    if sleep_dto and isinstance(sleep_dto, dict):
                        sleep_scores = sleep_dto.get("sleepScores")
                        if sleep_scores and isinstance(sleep_scores, dict):
                            overall = sleep_scores.get("overall")
                            if overall and isinstance(overall, dict):
                                value = overall.get("value")
                                if value is not None:
                                    return {"date": target_date, "sleep_score": int(value)}
            
            elif metric == "fitness_age":
                try:
                    fitness_data = self.client.get_my_fitness_age()
                    if fitness_data and isinstance(fitness_data, dict):
                        value = fitness_data.get("fitnessAge")
                        if value is not None:
                            # Garmin returns fitness age as decimal (e.g., 37.03692930253995)
                            # Store as hundredths of a year (e.g., 3704 for 37.04) to preserve 2 decimal places
                            return {"date": target_date, "fitness_age": int(round(float(value) * 100))}
                except Exception:
                    # Some devices don't support fitness age
                    pass
            
            elif metric == "hrv_weekly_avg":
                try:
                    today_iso = datetime.now().isoformat()[:10]
                    hrv_payload = self.client.get_hrv_data(today_iso)
                    if hrv_payload and isinstance(hrv_payload, dict):
                        hrv_summary = hrv_payload.get("hrvSummary", {})
                        weekly_avg = hrv_summary.get("weeklyAvg") or hrv_summary.get("7DayAvg")
                        if weekly_avg is None:
                            # Fallback: compute from baseline history
                            baseline_node = hrv_payload.get("baseline", {})
                            baseline_history = baseline_node.get("baselineHistory", [])
                            if baseline_history:
                                recent_values = [
                                    entry.get("lastNightAvg")
                                    for entry in baseline_history[-7:]
                                    if isinstance(entry, dict) and entry.get("lastNightAvg") is not None
                                ]
                                if recent_values:
                                    weekly_avg = sum(recent_values) // len(recent_values)
                        if weekly_avg is not None:
                            return {"date": target_date, "hrv_weekly_avg": int(weekly_avg)}
                except Exception:
                    pass
            
            elif metric == "steps":
                stats = self.client.get_stats_and_body(target_date)
                if stats and isinstance(stats, dict):
                    value = stats.get("steps")
                    if value is not None:
                        return {"date": target_date, "steps": int(value)}
            
            elif metric == "altitude_ascent_meters":
                stats = self.client.get_stats_and_body(target_date)
                if stats and isinstance(stats, dict):
                    value = stats.get("elevationGain")
                    if value is not None:
                        return {"date": target_date, "altitude_ascent_meters": int(value)}
            
            elif metric == "distance_meters":
                stats = self.client.get_stats_and_body(target_date)
                if stats and isinstance(stats, dict):
                    value = stats.get("distance")
                    if value is not None:
                        return {"date": target_date, "distance_meters": int(value)}
            
            elif metric == "calories":
                stats = self.client.get_stats_and_body(target_date)
                if stats and isinstance(stats, dict):
                    value = stats.get("calories")
                    if value is not None:
                        return {"date": target_date, "calories": int(value)}
            
            elif metric == "active_minutes":
                stats = self.client.get_stats_and_body(target_date)
                if stats and isinstance(stats, dict):
                    value = stats.get("activeMinutes") or stats.get("moderateIntensityMinutes")
                    if value is not None:
                        return {"date": target_date, "active_minutes": int(value)}
            
            elif metric == "floors_climbed":
                stats = self.client.get_stats_and_body(target_date)
                if stats and isinstance(stats, dict):
                    value = stats.get("floorsClimbed")
                    if value is not None:
                        return {"date": target_date, "floors_climbed": int(value)}
            
            # No data available for this metric/date
            return None
            
        except Exception as e:
            error_str = str(e).lower()
            if "rate limit" in error_str or "too many requests" in error_str or "429" in error_str:
                self._handle_rate_limit()
                return None
            else:
                logger.warning(f"Failed to fetch {metric} for {target_date}: {e}")
                return None
    
    def _mark_fetch_complete(self, metric: str, fetch_date: str):
        """Mark a metric/date pair as completed."""
        if metric not in self.state["completed_fetches"]:
            self.state["completed_fetches"][metric] = {}
        
        self.state["completed_fetches"][metric][fetch_date] = time.time()
        self._save_state()
    
    def _is_fetch_complete(self, metric: str, fetch_date: str) -> bool:
        """Check if a metric/date pair has been completed."""
        return (
            metric in self.state["completed_fetches"] and
            fetch_date in self.state["completed_fetches"][metric]
        )
    
    def _get_next_fetch_target(self) -> Optional[Tuple[str, str]]:
        """
        Determine the next (metric, date) pair to fetch.
        Priority: 7 days back → current day → continuous updates
        """
        today = date.today().isoformat()
        
        # Phase 1: Ensure we have 7 days back for all metrics
        for days_ago in range(HISTORICAL_DAYS, 0, -1):
            target_date = (date.today() - timedelta(days=days_ago)).isoformat()
            for metric in METRICS:
                if not self._is_fetch_complete(metric, target_date):
                    logger.info(
                        f"Phase 1: Fetching {metric} for {target_date} "
                        f"({days_ago} days ago)"
                    )
                    return (metric, target_date)
        
        # Phase 2: Fetch current day metrics
        for metric in METRICS:
            if not self._is_fetch_complete(metric, today):
                logger.info(f"Phase 2: Fetching {metric} for today ({today})")
                return (metric, today)
        
        # Phase 3: Continuous updates for current day
        # Check if enough time has passed since last continuous fetch
        now = time.time()
        if self.state["last_continuous_fetch"]:
            time_since_last = now - self.state["last_continuous_fetch"]
            if time_since_last < CONTINUOUS_FETCH_INTERVAL:
                logger.info(
                    f"Waiting {CONTINUOUS_FETCH_INTERVAL - time_since_last:.0f}s "
                    "before next continuous update..."
                )
                return None
        
        # Cycle through all metrics for today to get latest values
        for metric in METRICS:
            logger.info(f"Phase 3: Continuous update for {metric} today")
            self.state["last_continuous_fetch"] = now
            self._save_state()
            return (metric, today)
        
        return None
    
    def run(self):
        """Main run loop."""
        logger.info("Starting Garmin fetcher...")
        logger.info(f"Prioritizing {HISTORICAL_DAYS} days back, then current day, then continuous updates")
        
        while True:
            try:
                target = self._get_next_fetch_target()
                
                if target is None:
                    # Nothing to fetch right now, wait
                    time.sleep(10)
                    continue
                
                metric, fetch_date = target
                
                # Fetch the data
                result = self._fetch_single_metric(metric, fetch_date)
                
                if result is not None:
                    # Success - update cache and mark complete
                    for key, value in result.items():
                        if key != "date":
                            cache_metric = key
                            if cache_metric not in self.cache:
                                self.cache[cache_metric] = {}
                            self.cache[cache_metric][fetch_date] = value
                    
                    self._save_cache()
                    self._mark_fetch_complete(metric, fetch_date)
                    self._reset_rate_limit_tracking()
                    
                    logger.info(
                        f"Successfully fetched {metric} for {fetch_date}: "
                        f"{result.get(metric)}"
                    )
                    
                    # Small delay between successful requests to be safe
                    time.sleep(2)
                else:
                    # Rate limit or other error - already handled
                    pass
                
            except KeyboardInterrupt:
                logger.info("Shutting down Garmin fetcher...")
                break
            except Exception as e:
                logger.error(f"Unexpected error in main loop: {e}")
                time.sleep(10)


def main():
    """Main entry point."""
    email = os.getenv("GARMIN_EMAIL")
    password = os.getenv("GARMIN_PASSWORD")
    
    if not email or not password:
        logger.error(
            "GARMIN_EMAIL and GARMIN_PASSWORD environment variables must be set"
        )
        return
    
    fetcher = GarminFetcher(email, password)
    fetcher.run()


if __name__ == "__main__":
    main()