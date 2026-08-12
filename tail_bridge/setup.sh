#!/bin/bash
# Setup script for the Tail Bridge.
# Creates a venv, installs dependencies, and creates the .env file if missing.
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Tail Bridge Setup ==="

# Create venv
if [ ! -d "venv" ]; then
    echo "Creating virtual environment..."
    python3 -m venv venv
fi

# Install dependencies
echo "Installing dependencies..."
venv/bin/pip install -q -r requirements.txt

# Create .env from example if missing
if [ ! -f ".env" ]; then
    echo "Creating .env from .env.example..."
    cp .env.example .env
    echo "  → Edit .env to set your ANDROID_PROXY_KEY"
fi

echo ""
echo "=== Setup complete ==="
echo ""
echo "To start the bridge server manually:"
echo "  venv/bin/uvicorn bridge_server:app --host 0.0.0.0 --port 8001"
echo ""
echo "To start the movie watcher manually:"
echo "  venv/bin/python movie_watcher.py"
echo ""
echo "To install as systemd services (auto-start):"
echo "  mkdir -p ~/.config/systemd/user"
echo "  cp tail-bridge.service movie-watcher.service ~/.config/systemd/user/"
echo "  systemctl --user daemon-reload"
echo "  systemctl --user enable --now tail-bridge.service movie-watcher.service"
echo ""
echo "Test the server:"
echo "  curl http://localhost:8001/health"
