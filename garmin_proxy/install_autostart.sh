#!/bin/bash
# Install and enable systemd services for Garmin Proxy

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"

echo "Installing Garmin Proxy systemd services..."

# Create systemd user directory if it doesn't exist
mkdir -p "$SYSTEMD_USER_DIR"

# Copy service files
cp "$SCRIPT_DIR/garmin-proxy.service" "$SYSTEMD_USER_DIR/"
cp "$SCRIPT_DIR/garmin-fetch.service" "$SYSTEMD_USER_DIR/"
cp "$SCRIPT_DIR/garmin-fetch.timer" "$SYSTEMD_USER_DIR/"

# Reload systemd daemon
systemctl --user daemon-reload

# Enable services
echo "Enabling services..."
systemctl --user enable garmin-proxy.service
systemctl --user enable garmin-fetch.timer

# Start services
echo "Starting services..."
systemctl --user start garmin-proxy.service
systemctl --user start garmin-fetch.timer

echo ""
echo "Services installed and started!"
echo ""
echo "Check status with:"
echo "  systemctl --user status garmin-proxy.service"
echo "  systemctl --user status garmin-fetch.timer"
echo ""
echo "View logs with:"
echo "  journalctl --user -u garmin-proxy.service -f"
echo "  journalctl --user -u garmin-fetch.service -f"
echo ""
echo "IMPORTANT: You must run the initial authentication manually:"
echo "  cd $SCRIPT_DIR"
echo "  . venv/bin/activate && python3 auth_bridge.py"
echo ""
echo "After authentication, data will be fetched automatically every 30 minutes."