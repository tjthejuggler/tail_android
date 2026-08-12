#!/bin/bash
# ═══════════════════════════════════════════════════════════════════════════
# Tail Supervisor Installer
# ═══════════════════════════════════════════════════════════════════════════
#
# One-command setup that:
#   1. Creates venvs for garmin_proxy and tail_bridge (if missing)
#   2. Installs Python dependencies
#   3. Stops & disables old individual systemd services
#   4. Installs the single tail-supervisor.service
#   5. Starts everything
#
# After running this, the ONLY thing in autostart is tail-supervisor.service.
# To add new services in the future, just edit tail_services.toml and run:
#   systemctl --user restart tail-supervisor.service
# ═══════════════════════════════════════════════════════════════════════════

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SYSTEMD_USER_DIR="$HOME/.config/systemd/user"

echo "════════════════════════════════════════════════════════"
echo "  Tail Supervisor Installer"
echo "════════════════════════════════════════════════════════"
echo ""

# ── 1. Create venvs ────────────────────────────────────────────────────────
echo "▸ Setting up virtual environments…"

for subdir in garmin_proxy tail_bridge; do
    dir="$PROJECT_DIR/$subdir"
    if [ ! -d "$dir" ]; then
        echo "  ⚠ $subdir/ not found, skipping"
        continue
    fi
    if [ ! -d "$dir/venv" ]; then
        echo "  Creating venv for $subdir…"
        python3 -m venv "$dir/venv"
    fi
    if [ -f "$dir/requirements.txt" ]; then
        echo "  Installing dependencies for $subdir…"
        "$dir/venv/bin/pip" install -q -r "$dir/requirements.txt" 2>&1 | tail -1
    fi
done

echo ""

# ── 2. Stop & disable old individual services ──────────────────────────────
echo "▸ Cleaning up old individual services…"

OLD_SERVICES=(
    garmin-proxy.service
    garmin-fetch.timer
    garmin-fetch.service
    garmin-fetch-midnight.timer
    garmin-fetch-midnight.service
    tail-bridge.service
    movie-watcher.service
)

for svc in "${OLD_SERVICES[@]}"; do
    if systemctl --user is-enabled "$svc" &>/dev/null; then
        echo "  Disabling $svc"
        systemctl --user disable "$svc" 2>/dev/null || true
    fi
    if systemctl --user is-active "$svc" &>/dev/null; then
        echo "  Stopping $svc"
        systemctl --user stop "$svc" 2>/dev/null || true
    fi
    # Remove stale unit files
    rm -f "$SYSTEMD_USER_DIR/$svc"
done

echo ""

# ── 3. Install supervisor service ──────────────────────────────────────────
echo "▸ Installing tail-supervisor.service…"

mkdir -p "$SYSTEMD_USER_DIR"
cp "$PROJECT_DIR/tail-supervisor.service" "$SYSTEMD_USER_DIR/"

systemctl --user daemon-reload
systemctl --user enable tail-supervisor.service

echo ""

# ── 4. Start ───────────────────────────────────────────────────────────────
echo "▸ Starting supervisor…"
systemctl --user start tail-supervisor.service

sleep 3

echo ""
echo "════════════════════════════════════════════════════════"
echo "  ✓ Done!"
echo "════════════════════════════════════════════════════════"
echo ""
echo "Managed services (from tail_services.toml):"
echo "  $(systemctl --user status tail-supervisor.service --no-pager 2>&1 | head -5)"
echo ""
echo "Commands:"
echo "  Status:   systemctl --user status tail-supervisor.service"
echo "  Logs:     journalctl --user -u tail-supervisor.service -f"
echo "  Restart:  systemctl --user restart tail-supervisor.service"
echo "  Config:   nano $PROJECT_DIR/tail_services.toml"
echo ""
echo "To add a new service:"
echo "  1. Add a [[service]] block to tail_services.toml"
echo "  2. systemctl --user restart tail-supervisor.service"
echo ""
