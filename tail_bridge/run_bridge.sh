#!/usr/bin/env bash
# Starts the tail bridge with the .env loaded (ANDROID_PROXY_KEY etc.).
# Usage: ./run_bridge.sh  — logs to /tmp/tail-bridge.log
set -a
. "$(dirname "$0")/.env"
set +a
exec "$(dirname "$0")/venv/bin/uvicorn" bridge_server:app --host 0.0.0.0 --port 8001
