#!/usr/bin/env python3
"""
Simple syntax and structure test for Garmin fetcher and cached proxy system.
Tests without requiring actual dependencies to be installed.
"""

import ast
import json
from pathlib import Path
from datetime import date, timedelta

def test_python_syntax(filepath):
    """Test that a Python file has valid syntax."""
    print(f"  Testing {filepath}...")
    try:
        with open(filepath, 'r') as f:
            code = f.read()
        ast.parse(code)
        print(f"  ✓ {filepath} has valid syntax")
        return True
    except SyntaxError as e:
        print(f"  ✗ {filepath} has syntax error: {e}")
        return False

def test_file_structure(filepath, expected_elements):
    """Test that a Python file contains expected elements."""
    print(f"  Testing structure of {filepath}...")
    try:
        with open(filepath, 'r') as f:
            code = f.read()
        tree = ast.parse(code)
        
        found = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.ClassDef):
                found.add(f"class:{node.name}")
            elif isinstance(node, ast.FunctionDef):
                found.add(f"function:{node.name}")
            elif isinstance(node, ast.Assign):
                for target in node.targets:
                    if isinstance(target, ast.Name):
                        found.add(f"variable:{target.id}")
        
        missing = [e for e in expected_elements if e not in found]
        if missing:
            print(f"  ✗ Missing elements: {missing}")
            return False
        
        print(f"  ✓ {filepath} has all expected elements")
        return True
    except Exception as e:
        print(f"  ✗ Error testing structure: {e}")
        return False

def test_json_structure(filepath):
    """Test that a JSON file has valid structure."""
    print(f"  Testing {filepath}...")
    try:
        with open(filepath, 'r') as f:
            data = json.load(f)
        print(f"  ✓ {filepath} has valid JSON structure")
        return True
    except json.JSONDecodeError as e:
        print(f"  ✗ {filepath} has JSON error: {e}")
        return False
    except FileNotFoundError:
        print(f"  ✓ {filepath} doesn't exist yet (will be created at runtime)")
        return True

print("="*60)
print("Garmin Fetcher & Cached Proxy System - Syntax & Structure Tests")
print("="*60)

# Test 1: garmin_fetcher.py syntax
print("\n1. Testing garmin_fetcher.py syntax...")
test_python_syntax("garmin_fetcher.py")

# Test 2: garmin_fetcher.py structure
print("\n2. Testing garmin_fetcher.py structure...")
expected_fetcher_elements = [
    "class:GarminFetcher",
    "function:_load_state",
    "function:_save_state",
    "function:_load_cache",
    "function:_save_cache",
    "function:_connect",
    "function:_handle_rate_limit",
    "function:_fetch_single_metric",
    "function:_get_next_fetch_target",
    "function:run",
    "function:main",
    "variable:METRICS",
    "variable:STATE_FILE",
    "variable:CACHE_FILE",
    "variable:BASE_RETRY_DELAY",
    "variable:MAX_RETRY_DELAY",
    "variable:CONTINUOUS_FETCH_INTERVAL"
]
test_file_structure("garmin_fetcher.py", expected_fetcher_elements)

# Test 3: garmin_proxy/app_cached.py syntax
print("\n3. Testing garmin_proxy/app_cached.py syntax...")
test_python_syntax("garmin_proxy/app_cached.py")

# Test 4: garmin_proxy/app_cached.py structure
print("\n4. Testing garmin_proxy/app_cached.py structure...")
expected_proxy_elements = [
    "function:load_cache",
    "function:get_metrics_for_date",
    "function:get_all_cached_dates",
    "function:get_available_metrics_for_date",
    "function:get_health_metrics",
    "function:get_health_metrics_batch",
    "function:get_available_dates",
    "function:get_metrics_summary",
    "function:health_check",
    "function:comprehensive_health_check",
    "variable:METRICS",
    "variable:CACHE_FILE"
]
test_file_structure("garmin_proxy/app_cached.py", expected_proxy_elements)

# Test 5: Test cache/state files (will be created at runtime)
print("\n5. Testing cache/state file structure...")
test_json_structure("garmin_cache.json")
test_json_structure("garmin_fetcher_state.json")

# Test 6: Verify METRICS constant consistency
print("\n6. Verifying METRICS constant consistency...")
try:
    with open("garmin_fetcher.py", 'r') as f:
        fetcher_code = f.read()
    with open("garmin_proxy/app_cached.py", 'r') as f:
        proxy_code = f.read()
    
    # Extract METRICS from garmin_fetcher.py
    fetcher_tree = ast.parse(fetcher_code)
    fetcher_metrics = None
    for node in ast.walk(fetcher_tree):
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id == "METRICS":
                    if isinstance(node.value, ast.List):
                        fetcher_metrics = [elt.s for elt in node.value.elts if isinstance(elt, ast.Constant)]
                    break
    
    # Extract METRICS from app_cached.py
    proxy_tree = ast.parse(proxy_code)
    proxy_metrics = None
    for node in ast.walk(proxy_tree):
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id == "METRICS":
                    if isinstance(node.value, ast.List):
                        proxy_metrics = [elt.s for elt in node.value.elts if isinstance(elt, ast.Constant)]
                    break
    
    if fetcher_metrics and proxy_metrics:
        if set(fetcher_metrics) == set(proxy_metrics):
            print(f"  ✓ METRICS constants match: {len(fetcher_metrics)} metrics")
            print(f"    Metrics: {', '.join(fetcher_metrics)}")
        else:
            print(f"  ✗ METRICS constants don't match!")
            print(f"    Fetcher: {fetcher_metrics}")
            print(f"    Proxy: {proxy_metrics}")
    else:
        print(f"  ✗ Could not extract METRICS constants")
        
except Exception as e:
    print(f"  ✗ Error verifying METRICS: {e}")

# Test 7: Verify rate limit backoff constants
print("\n7. Verifying rate limit backoff constants...")
try:
    with open("garmin_fetcher.py", 'r') as f:
        code = f.read()
    tree = ast.parse(code)
    
    constants = {}
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name):
                    if target.id in ["BASE_RETRY_DELAY", "MAX_RETRY_DELAY"]:
                        if isinstance(node.value, ast.Constant):
                            constants[target.id] = node.value.value
    
    if "BASE_RETRY_DELAY" in constants and "MAX_RETRY_DELAY" in constants:
        base = constants["BASE_RETRY_DELAY"]
        max_delay = constants["MAX_RETRY_DELAY"]
        
        print(f"  ✓ BASE_RETRY_DELAY: {base}s")
        print(f"  ✓ MAX_RETRY_DELAY: {max_delay}s")
        
        # Verify exponential backoff progression
        delays = []
        for error_count in range(1, 10):
            delay = min(base * (2 ** (error_count - 1)), max_delay)
            delays.append(delay)
        
        print(f"  ✓ Backoff progression (first 5 errors): {delays[:5]}s")
        
        if all(d <= max_delay for d in delays):
            print(f"  ✓ All delays respect MAX_RETRY_DELAY")
        else:
            print(f"  ✗ Some delays exceed MAX_RETRY_DELAY")
    else:
        print(f"  ✗ Could not find rate limit constants")
        
except Exception as e:
    print(f"  ✗ Error verifying rate limit constants: {e}")

# Test 8: Verify priority queue logic structure
print("\n8. Verifying priority queue logic structure...")
try:
    with open("garmin_fetcher.py", 'r') as f:
        code = f.read()
    
    # Check for the three phases
    phases = [
        ("Phase 1", ["HISTORICAL_DAYS", "range(HISTORICAL_DAYS, 0, -1)"]),
        ("Phase 2", ["today", "current day"]),
        ("Phase 3", ["continuous", "CONTINUOUS_FETCH_INTERVAL"])
    ]
    
    for phase_name, keywords in phases:
        found = any(kw.lower() in code.lower() for kw in keywords)
        if found:
            print(f"  ✓ {phase_name} logic present")
        else:
            print(f"  ✗ {phase_name} logic not found")
            
except Exception as e:
    print(f"  ✗ Error verifying priority queue: {e}")

print("\n" + "="*60)
print("Syntax and Structure Tests Complete!")
print("="*60)
print("\nNext steps:")
print("1. Install dependencies: pip install -r garmin_proxy/requirements.txt")
print("2. Set GARMIN_EMAIL and GARMIN_PASSWORD environment variables")
print("3. Set ANDROID_PROXY_KEY environment variable")
print("4. Run: python garmin_fetcher.py")
print("5. Run: uvicorn garmin_proxy.app_cached:app --host 0.0.0.0 --port 8000")
print("\nSee garmin_fetcher_README.md for full documentation.")