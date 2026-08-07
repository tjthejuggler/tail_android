"""
Garmin Data Fetching Module (token-based)

Fetches the last N days of Garmin health metrics and caches them locally.

Authentication:
    Resumes from the OAuth tokens saved by auth_bridge.py (default token store:
    ~/.garminconnect). This performs NO login on a normal run, which is the key
    to avoiding Garmin's 429 rate limits (Garmin rate-limits logins, not reads).

    If the tokens are missing or expired, this script exits with a clear message
    telling you to re-run auth_bridge.py. It NEVER logs in on its own and never
    loops a browser - that is what got the account rate-limited before.

Rate limiting:
    Enforces a minimum 15-minute interval between fetches.

Local caching:
    All fetched data is written to garmin_cache.json in the shape:
        {"data": {"YYYY-MM-DD": {metrics...}}, "metadata": {...}}
    The application layer (app.py / the Android app) queries this cache, NOT the
    Garmin API directly.
"""

import os
import json
import time
import logging
import datetime
import argparse
import subprocess
from pathlib import Path
from typing import Dict, Any, Optional, List

from garminconnect import (
    Garmin,
    GarminConnectAuthenticationError,
    GarminConnectTooManyRequestsError,
)
try:
    from garth.exc import GarthHTTPError
except Exception:  # pragma: no cover - garth internal layout may vary
    class GarthHTTPError(Exception):
        pass

# Load environment variables from .env file if present
env_path = Path(__file__).parent / ".env"
if env_path.exists():
    from dotenv import load_dotenv
    load_dotenv(env_path)

logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

# Configuration
CACHE_FILE = Path(__file__).parent / "garmin_cache.json"
LAST_FETCH_FILE = Path(__file__).parent / ".last_fetch_timestamp"
TOKEN_STORE = os.getenv("GARMINTOKENS", os.path.expanduser("~/.garminconnect"))
MIN_FETCH_INTERVAL_SECONDS = 15 * 60  # 15 minutes
DEFAULT_DAYS = 7
# Small polite delay between API calls within a single run.
INTER_REQUEST_DELAY = 1.0


class TokenExpiredError(Exception):
    """Raised when the saved OAuth tokens are missing or no longer valid."""


# --------------------------------------------------------------------------- #
# Rogue-process detection
# --------------------------------------------------------------------------- #
# The legacy garmin_fetcher.py logs in on every metric fetch. If it is still
# running (directly or via garmin-fetcher.service), it will keep the IP
# rate-limited so that even token-based reads start failing with 429.
ROGUE_PROCESS_PATTERNS = ["garmin_fetcher.py"]
ROGUE_SERVICES = ["garmin-fetcher.service"]


def _check_for_rogue_processes() -> List[str]:
    """
    Detect legacy fetcher processes/services that login on every run.

    Returns a list of human-readable warning strings. Non-fatal: the caller
    logs them as warnings but still attempts the fetch (token reads may still
    work even if the IP is partially limited).
    """
    warnings: List[str] = []

    try:
        result = subprocess.run(
            ["pgrep", "-af", "|".join(ROGUE_PROCESS_PATTERNS)],
            capture_output=True, text=True, timeout=5,
        )
        if result.returncode == 0 and result.stdout.strip():
            for line in result.stdout.strip().splitlines():
                if "pgrep" not in line and line.strip():
                    warnings.append(f"Rogue process: {line.strip()}")
    except Exception:
        pass

    for svc in ROGUE_SERVICES:
        try:
            result = subprocess.run(
                ["systemctl", "--user", "is-active", svc],
                capture_output=True, text=True, timeout=5,
            )
            if result.returncode == 0 and result.stdout.strip() == "active":
                warnings.append(
                    f"Rogue service '{svc}' is active. "
                    f"Stop it: systemctl --user stop {svc}"
                )
        except Exception:
            pass

    return warnings


# --------------------------------------------------------------------------- #
# Rate limiting (between whole fetch runs)
# --------------------------------------------------------------------------- #
def get_last_fetch_timestamp() -> Optional[float]:
    try:
        if LAST_FETCH_FILE.exists():
            return float(LAST_FETCH_FILE.read_text().strip())
    except Exception as e:
        logger.warning(f"Could not read last fetch timestamp: {e}")
    return None


def update_last_fetch_timestamp() -> None:
    try:
        LAST_FETCH_FILE.write_text(str(time.time()))
    except Exception as e:
        logger.warning(f"Could not write last fetch timestamp: {e}")


def can_fetch_now() -> bool:
    last_fetch = get_last_fetch_timestamp()
    if last_fetch is None:
        return True
    elapsed = time.time() - last_fetch
    if elapsed < MIN_FETCH_INTERVAL_SECONDS:
        remaining = int(MIN_FETCH_INTERVAL_SECONDS - elapsed)
        logger.info(f"Rate limit active. Next fetch allowed in {remaining} seconds.")
        return False
    return True


# --------------------------------------------------------------------------- #
# Cache
# --------------------------------------------------------------------------- #
def load_cache() -> Dict[str, Any]:
    try:
        if CACHE_FILE.exists():
            with open(CACHE_FILE, "r") as f:
                return json.load(f)
    except Exception as e:
        logger.warning(f"Could not load cache: {e}")
    return {"data": {}, "metadata": {}}


def save_cache(data: Dict[str, Any]) -> None:
    try:
        with open(CACHE_FILE, "w") as f:
            json.dump(data, f, indent=2)
        logger.info(f"Cache saved to {CACHE_FILE}")
    except Exception as e:
        logger.error(f"Could not save cache: {e}")


# --------------------------------------------------------------------------- #
# Authentication (resume only - never login here)
# --------------------------------------------------------------------------- #
def get_client() -> Garmin:
    """
    Build a Garmin client by resuming from the saved token store.

    No credentials are passed, so the fork CANNOT silently log in: if the saved
    tokens are missing/expired it raises (we surface that as TokenExpiredError),
    which is exactly what keeps this script from triggering login rate limits.
    """
    if not Path(TOKEN_STORE).exists():
        raise TokenExpiredError(
            f"No saved Garmin tokens at {TOKEN_STORE}. Run auth_bridge.py first."
        )
    try:
        client = Garmin()  # no email/password -> resume-only
        client.login(TOKEN_STORE)
        return client
    except GarminConnectTooManyRequestsError as e:
        # A token refresh got 429'd. Treat as transient, not a credential issue.
        raise TokenExpiredError(
            f"Garmin rate-limited the token refresh (429): {e}. "
            "Wait a while, then retry; if it persists, re-run auth_bridge.py."
        )
    except (
        GarminConnectAuthenticationError,
        GarthHTTPError,
        FileNotFoundError,
        KeyError,
    ) as e:
        raise TokenExpiredError(
            f"Saved Garmin tokens are invalid/expired ({e}). Re-run auth_bridge.py."
        )


# --------------------------------------------------------------------------- #
# Metric extraction
# --------------------------------------------------------------------------- #
def _safe(callable_fn, *args):
    """Call a Garmin API method, returning None on per-metric failure."""
    try:
        return callable_fn(*args)
    except Exception as e:
        logger.warning(f"Garmin API call {getattr(callable_fn, '__name__', callable_fn)} failed: {e}")
        return None


def fetch_daily_metrics(client: Garmin, date_str: str) -> Dict[str, Any]:
    """
    Fetch all available health metrics for a single date.

    Uses the same field paths already validated by the repo's garmin_fetcher.py.
    """
    metrics: Dict[str, Any] = {
        "date": date_str,
        "vo2_max": None,
        "resting_hr": None,
        "min_hr": None,
        "max_hr": None,
        "hrv_last_night": None,
        "sleep_score": None,
        "fitness_age": None,
        "hrv_weekly_avg": None,
        "steps": None,
        "altitude_ascent_meters": None,
        "distance_meters": None,
        "calories": None,
        "active_minutes": None,
        "floors_climbed": None,
        "stress_score": None,
    }

    # --- get_stats_and_body covers several fields in one call ---
    stats = _safe(client.get_stats_and_body, date_str)
    if isinstance(stats, dict):
        if stats.get("restingHeartRate") is not None:
            metrics["resting_hr"] = int(stats["restingHeartRate"])
        if stats.get("totalSteps") is not None:
            metrics["steps"] = int(stats["totalSteps"])
        elif stats.get("steps") is not None:
            metrics["steps"] = int(stats["steps"])
        # Garmin exposes daily ascent as floorsAscendedInMeters (meters climbed).
        ascent = stats.get("floorsAscendedInMeters")
        if ascent is None:
            ascent = stats.get("elevationGain")  # legacy/fallback field name
        if ascent is not None:
            metrics["altitude_ascent_meters"] = int(ascent)
        if stats.get("totalDistanceMeters") is not None:
            metrics["distance_meters"] = int(stats["totalDistanceMeters"])
        elif stats.get("distance") is not None:
            metrics["distance_meters"] = int(stats["distance"])
        if stats.get("totalKilocalories") is not None:
            metrics["calories"] = int(stats["totalKilocalories"])
        elif stats.get("calories") is not None:
            metrics["calories"] = int(stats["calories"])
        # Active minutes = moderate + vigorous intensity minutes
        moderate = stats.get("moderateIntensityMinutes") or 0
        vigorous = stats.get("vigorousIntensityMinutes") or 0
        if moderate is not None or vigorous is not None:
            metrics["active_minutes"] = int((moderate or 0) + (vigorous or 0))
        if stats.get("floorsAscended") is not None:
            metrics["floors_climbed"] = int(stats["floorsAscended"])
        elif stats.get("floorsClimbed") is not None:
            metrics["floors_climbed"] = int(stats["floorsClimbed"])
    time.sleep(INTER_REQUEST_DELAY)

    # --- VO2 max: prefer the dedicated max-metrics endpoint, fall back to
    # the training-status payload (field paths validated by garmin_fetcher.py) ---
    max_metrics = _safe(client.get_max_metrics, date_str)
    if isinstance(max_metrics, list) and max_metrics:
        entry = max_metrics[0]
        if isinstance(entry, dict):
            generic = entry.get("generic")
            if isinstance(generic, dict) and generic.get("vo2MaxValue") is not None:
                metrics["vo2_max"] = generic["vo2MaxValue"]
    if metrics["vo2_max"] is None:
        training = _safe(client.get_training_status, date_str)
        if isinstance(training, dict):
            vo2_node = training.get("mostRecentVO2Max")
            if isinstance(vo2_node, dict):
                generic = vo2_node.get("generic")
                if isinstance(generic, dict) and generic.get("vo2MaxValue") is not None:
                    metrics["vo2_max"] = generic["vo2MaxValue"]
    time.sleep(INTER_REQUEST_DELAY)

    # --- Sleep: score + last night's HRV ---
    sleep_data = _safe(client.get_sleep_data, date_str)
    if isinstance(sleep_data, dict):
        if sleep_data.get("avgOvernightHrv") is not None:
            metrics["hrv_last_night"] = int(sleep_data["avgOvernightHrv"])
        sleep_dto = sleep_data.get("dailySleepDTO")
        if isinstance(sleep_dto, dict):
            scores = sleep_dto.get("sleepScores")
            if isinstance(scores, dict):
                overall = scores.get("overall")
                if isinstance(overall, dict) and overall.get("value") is not None:
                    metrics["sleep_score"] = int(overall["value"])
    time.sleep(INTER_REQUEST_DELAY)

    # --- HRV weekly average ---
    hrv_payload = _safe(client.get_hrv_data, date_str)
    if isinstance(hrv_payload, dict):
        hrv_summary = hrv_payload.get("hrvSummary", {}) or {}
        weekly_avg = hrv_summary.get("weeklyAvg") or hrv_summary.get("7DayAvg")
        if weekly_avg is None:
            baseline = hrv_payload.get("baseline", {}) or {}
            history = baseline.get("baselineHistory", []) or []
            recent = [
                e.get("lastNightAvg")
                for e in history[-7:]
                if isinstance(e, dict) and e.get("lastNightAvg") is not None
            ]
            if recent:
                weekly_avg = sum(recent) // len(recent)
        if weekly_avg is not None:
            metrics["hrv_weekly_avg"] = int(weekly_avg)
    time.sleep(INTER_REQUEST_DELAY)

    # --- Heart rate: min and max ---
    heart_data = _safe(client.get_heart_rates, date_str)
    if isinstance(heart_data, dict):
        if heart_data.get("minHeartRate") is not None:
            metrics["min_hr"] = int(heart_data["minHeartRate"])
        if heart_data.get("maxHeartRate") is not None:
            metrics["max_hr"] = int(heart_data["maxHeartRate"])
    time.sleep(INTER_REQUEST_DELAY)

    # --- Stress: average daily stress score ---
    stress_data = _safe(client.get_all_day_stress, date_str)
    if isinstance(stress_data, dict):
        if stress_data.get("avgStressLevel") is not None:
            metrics["stress_score"] = int(stress_data["avgStressLevel"])
    time.sleep(INTER_REQUEST_DELAY)

    logger.info(f"Fetched metrics for {date_str}")
    return metrics


def fetch_fitness_age(client: Garmin, date_str: str) -> Optional[int]:
    """Fitness age may be unsupported on some devices/accounts."""
    data = _safe(client.get_fitnessage_data, date_str)
    if isinstance(data, dict) and data.get("fitnessAge") is not None:
        # Garmin returns fitness age as decimal (e.g., 37.03692930253995)
        # Store as hundredths of a year (e.g., 3704 for 37.04) to preserve 2 decimal places
        return int(round(float(data["fitnessAge"]) * 100))
    return None


# --------------------------------------------------------------------------- #
# Orchestration
# --------------------------------------------------------------------------- #
def fetch_garmin_data(days: int = DEFAULT_DAYS, force: bool = False) -> Dict[str, Any]:
    """
    Fetch Garmin data for the last `days` days and update the local cache.

    Args:
        days: Number of days to fetch (default: 7)
        force: Bypass rate limiting and force a fresh fetch

    Returns the cache dict. On token expiry, raises TokenExpiredError.
    """
    if not force and not can_fetch_now():
        logger.info("Skipping fetch due to rate limiting; returning existing cache.")
        return load_cache()

    # Warn (non-fatal) if the legacy per-run-login fetcher is still active.
    rogue_warnings = _check_for_rogue_processes()
    if rogue_warnings:
        logger.warning("=" * 60)
        logger.warning("WARNING: A legacy Garmin fetcher is still running!")
        logger.warning("It logs in on every metric fetch, which causes 429 IP")
        logger.warning("rate-limits that can block even token-based reads.")
        logger.warning("=" * 60)
        for w in rogue_warnings:
            logger.warning(f"  • {w}")
        logger.warning("Stop it, then wait ~30-60 min for the IP block to clear.")
        logger.warning("=" * 60)

    client = get_client()

    cache = load_cache()
    cache.setdefault("data", {})
    cache.setdefault("metadata", {})

    today = datetime.date.today()
    dates = [(today - datetime.timedelta(days=i)).isoformat() for i in range(days)]
    logger.info(f"Fetching {len(dates)} days: {dates}")

    for date_str in dates:
        metrics = fetch_daily_metrics(client, date_str)
        fitness_age = fetch_fitness_age(client, date_str)
        if fitness_age is not None:
            metrics["fitness_age"] = fitness_age
        # Merge so we never wipe a previously-captured value with a None.
        existing = cache["data"].get(date_str, {})
        for k, v in metrics.items():
            if v is not None or k not in existing:
                existing[k] = v
        cache["data"][date_str] = existing

    cache["metadata"]["last_updated"] = datetime.datetime.now().isoformat()

    save_cache(cache)
    update_last_fetch_timestamp()
    logger.info("Data fetch completed successfully.")
    return cache


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Fetch Garmin health data")
    parser.add_argument("--days", type=int, default=DEFAULT_DAYS,
                        help=f"Number of days to fetch (default: {DEFAULT_DAYS})")
    parser.add_argument("--force", action="store_true",
                        help="Bypass rate limiting and force a fresh fetch")
    args = parser.parse_args()

    try:
        cache = fetch_garmin_data(days=args.days, force=args.force)
        print(f"\nCache contains data for {len(cache.get('data', {}))} days")
        print(f"Last updated: {cache.get('metadata', {}).get('last_updated', 'Unknown')}")
    except TokenExpiredError as e:
        # Explicit, actionable exit. auth_bridge.py is the ONLY thing that logs in.
        logger.error(str(e))
        logger.error("Run: . venv/bin/activate && python3 auth_bridge.py")
        raise SystemExit(2)
    except Exception as e:
        logger.error(f"Error: {e}")
        raise SystemExit(1)
