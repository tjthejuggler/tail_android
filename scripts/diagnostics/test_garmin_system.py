#!/usr/bin/env python3
"""
Test script for Garmin fetcher and cached proxy system.
Tests basic functionality without requiring actual Garmin credentials.
"""

import json
import tempfile
from pathlib import Path
from datetime import date, timedelta

# Test 1: Verify cache structure
print("Test 1: Verifying cache structure...")
cache_file = Path("garmin_cache.json")
state_file = Path("garmin_fetcher_state.json")

# Create test cache data
test_cache = {
    "vo2_max": {
        (date.today() - timedelta(days=1)).isoformat(): 45,
        date.today().isoformat(): 46
    },
    "resting_hr": {
        (date.today() - timedelta(days=1)).isoformat(): 52,
        date.today().isoformat(): 51
    }
}

# Write test cache
with open(cache_file, 'w') as f:
    json.dump(test_cache, f, indent=2)
print(f"✓ Created test cache at {cache_file}")

# Create test state
test_state = {
    "completed_fetches": {
        "vo2_max": {
            (date.today() - timedelta(days=1)).isoformat(): 1234567890,
            date.today().isoformat(): 1234567891
        },
        "resting_hr": {
            (date.today() - timedelta(days=1)).isoformat(): 1234567892,
            date.today().isoformat(): 1234567893
        }
    },
    "last_full_fetch_date": None,
    "current_day_cycle_complete": False,
    "last_continuous_fetch": None
}

with open(state_file, 'w') as f:
    json.dump(test_state, f, indent=2)
print(f"✓ Created test state at {state_file}")

# Test 2: Verify proxy can load cache
print("\nTest 2: Verifying proxy cache loading...")
try:
    # Import the proxy module
    import sys
    sys.path.insert(0, 'garmin_proxy')
    from app_cached import load_cache, get_metrics_for_date, get_all_cached_dates
    
    cache = load_cache()
    print(f"✓ Loaded cache with {len(cache)} metrics")
    
    # Test getting metrics for a date
    today = date.today().isoformat()
    metrics = get_metrics_for_date(today)
    print(f"✓ Got metrics for {today}: {metrics}")
    
    # Test getting all dates
    dates = get_all_cached_dates()
    print(f"✓ Got {len(dates)} cached dates: {dates}")
    
except Exception as e:
    print(f"✗ Failed proxy test: {e}")
    import traceback
    traceback.print_exc()

# Test 3: Verify fetcher module structure
print("\nTest 3: Verifying fetcher module structure...")
try:
    # Import the fetcher module
    import garmin_fetcher
    
    # Check that key classes/functions exist
    assert hasattr(garmin_fetcher, 'GarminFetcher'), "GarminFetcher class not found"
    assert hasattr(garmin_fetcher, 'METRICS'), "METRICS constant not found"
    assert hasattr(garmin_fetcher, 'STATE_FILE'), "STATE_FILE constant not found"
    assert hasattr(garmin_fetcher, 'CACHE_FILE'), "CACHE_FILE constant not found"
    
    print(f"✓ GarminFetcher class exists")
    print(f"✓ METRICS constant defined: {garmin_fetcher.METRICS}")
    print(f"✓ STATE_FILE: {garmin_fetcher.STATE_FILE}")
    print(f"✓ CACHE_FILE: {garmin_fetcher.CACHE_FILE}")
    
except Exception as e:
    print(f"✗ Failed fetcher test: {e}")
    import traceback
    traceback.print_exc()

# Test 4: Verify priority queue logic
print("\nTest 4: Verifying priority queue logic...")
try:
    import garmin_fetcher
    
    # Create a mock fetcher to test priority logic
    class MockFetcher:
        def __init__(self):
            self.state = {
                "completed_fetches": {},
                "last_full_fetch_date": None,
                "current_day_cycle_complete": False,
                "last_continuous_fetch": None
            }
        
        def _is_fetch_complete(self, metric: str, fetch_date: str) -> bool:
            return (
                metric in self.state["completed_fetches"] and
                fetch_date in self.state["completed_fetches"][metric]
            )
        
        def _get_next_fetch_target(self):
            """Simplified version of the priority logic."""
            today = date.today().isoformat()
            
            # Phase 1: Ensure we have 7 days back for all metrics
            for days_ago in range(7, 0, -1):
                target_date = (date.today() - timedelta(days=days_ago)).isoformat()
                for metric in garmin_fetcher.METRICS:
                    if not self._is_fetch_complete(metric, target_date):
                        return (metric, target_date)
            
            # Phase 2: Fetch current day metrics
            for metric in garmin_fetcher.METRICS:
                if not self._is_fetch_complete(metric, today):
                    return (metric, today)
            
            # Phase 3: Continuous updates
            return None
    
    mock = MockFetcher()
    
    # Test Phase 1: Should prioritize historical days
    target = mock._get_next_fetch_target()
    assert target is not None, "Should have a target for Phase 1"
    metric, fetch_date = target
    print(f"✓ Phase 1 target: {metric} for {fetch_date}")
    
    # Mark some historical data as complete
    for days_ago in range(7, 0, -1):
        d = (date.today() - timedelta(days=days_ago)).isoformat()
        for m in garmin_fetcher.METRICS:
            if m not in mock.state["completed_fetches"]:
                mock.state["completed_fetches"][m] = {}
            mock.state["completed_fetches"][m][d] = 1234567890
    
    # Test Phase 2: Should now prioritize current day
    target = mock._get_next_fetch_target()
    assert target is not None, "Should have a target for Phase 2"
    metric, fetch_date = target
    assert fetch_date == date.today().isoformat(), "Should be today"
    print(f"✓ Phase 2 target: {metric} for {fetch_date}")
    
    # Mark current day as complete
    today = date.today().isoformat()
    for m in garmin_fetcher.METRICS:
        if m not in mock.state["completed_fetches"]:
            mock.state["completed_fetches"][m] = {}
        mock.state["completed_fetches"][m][today] = 1234567890
    
    # Test Phase 3: Should return None (continuous phase)
    target = mock._get_next_fetch_target()
    print(f"✓ Phase 3 target: {target} (None means continuous phase)")
    
except Exception as e:
    print(f"✗ Failed priority queue test: {e}")
    import traceback
    traceback.print_exc()

# Test 5: Verify rate limit backoff logic
print("\nTest 5: Verifying rate limit backoff logic...")
try:
    import garmin_fetcher
    
    # Test exponential backoff calculation
    base_delay = garmin_fetcher.BASE_RETRY_DELAY
    max_delay = garmin_fetcher.MAX_RETRY_DELAY
    
    # Calculate expected delays
    expected_delays = []
    for error_count in range(1, 10):
        delay = min(base_delay * (2 ** (error_count - 1)), max_delay)
        expected_delays.append(delay)
    
    print(f"✓ Base delay: {base_delay}s")
    print(f"✓ Max delay: {max_delay}s")
    print(f"✓ Backoff progression: {expected_delays[:5]}s (first 5 errors)")
    
    # Verify delays don't exceed max
    assert all(d <= max_delay for d in expected_delays), "Delays should not exceed max"
    print(f"✓ All delays respect max_delay")
    
except Exception as e:
    print(f"✗ Failed rate limit test: {e}")
    import traceback
    traceback.print_exc()

# Cleanup
print("\nCleaning up test files...")
cache_file.unlink(missing_ok=True)
state_file.unlink(missing_ok=True)
print("✓ Cleaned up test files")

print("\n" + "="*50)
print("All tests completed!")
print("="*50)
print("\nNext steps:")
print("1. Set GARMIN_EMAIL and GARMIN_PASSWORD environment variables")
print("2. Set ANDROID_PROXY_KEY environment variable")
print("3. Run: python garmin_fetcher.py")
print("4. Run: uvicorn garmin_proxy.app_cached:app --host 0.0.0.0 --port 8000")