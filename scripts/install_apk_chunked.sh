#!/usr/bin/env bash
# install_apk_chunked.sh — reliable APK install over flaky wireless-ADB links.
#
# Why: a single `adb install` streams the whole APK (232 MB debug build) over
# one connection; on high-jitter links (Tailscale MTU 1280, RTT spikes) the
# stream dies mid-transfer. This script splits the APK into small chunks,
# pushes each with independent retries + reconnect-with-state-polling, SKIPS
# chunks already fully transferred (resumable across runs), reassembles
# on-device, verifies the byte size, installs via `pm install`, cleans up.
#
# Usage: ./scripts/install_apk_chunked.sh [serial] [apk]
set -uo pipefail

APK="${2:-app/build/outputs/apk/debug/app-debug.apk}"
CHUNK_MB=8
MAX_TRIES=10
TMPDIR_REMOTE="/data/local/tmp"
# Stable remote base (not $$-random) so interrupted runs can resume.
REMOTE_BASE="$TMPDIR_REMOTE/tail_apk_parts"

if [[ ! -f "$APK" ]]; then
    echo "APK not found: $APK" >&2
    exit 1
fi

SERIAL="${1:-${ADB_SERIAL:-}}"
if [[ -z "$SERIAL" ]]; then
    mapfile -t devs < <(adb devices | awk '$2=="device"{print $1}')
    if [[ ${#devs[@]} -eq 1 ]]; then SERIAL="${devs[0]}"; fi
    if [[ ${#devs[@]} -ne 1 ]]; then
        echo "Multiple/no devices; pass a serial or set ADB_SERIAL." >&2
        adb devices >&2
        exit 1
    fi
fi
ADB() { adb -s "$SERIAL" "$@"; }
HOST="${SERIAL%%:*}:5555"

echo "==> Target: $SERIAL   APK: $APK ($(du -m "$APK" | cut -f1) MB)"

wait_for_device() {
    # Poll up to ~45 s for the device to reach the "device" state.
    for _ in $(seq 1 15); do
        if [[ "$(adb devices | awk -v s="$SERIAL" '$1==s{print $2}')" == "device" ]]; then
            return 0
        fi
        ADB disconnect >/dev/null 2>&1 || true
        sleep 2
        adb connect "$HOST" >/dev/null 2>&1 || true
        sleep 3
    done
    return 1
}

# ── Single-endpoint enforcement ─────────────────────────────────────────────
# One phone can be reachable via SEVERAL IPs at once (Tailscale + LAN). If
# two endpoints for the same device are connected, adb round-robins between
# two live transports — causing phantom "more than one device" errors and
# flapping "device not found". Keep ONLY our endpoint connected.
mapfile -t other_eps < <(adb devices | awk '$2=="device"{print $1}' | grep -v "^$SERIAL$")
for ep in "${other_eps[@]:-}"; do
    [[ -n "$ep" ]] || continue
    echo "==> Disconnecting duplicate endpoint for the same device: $ep"
    adb disconnect "$ep" >/dev/null 2>&1 || true
done

if ! wait_for_device; then
    echo "Device never reached 'device' state; aborting." >&2
    exit 1
fi

# 1. Split locally
SPLIT_DIR="$(mktemp -d)"
trap 'rm -rf "$SPLIT_DIR"' EXIT
split -b "${CHUNK_MB}m" -d -a 3 "$APK" "$SPLIT_DIR/chunk_"
CHUNKS=("$SPLIT_DIR"/chunk_*)
N=${#CHUNKS[@]}
echo "==> $N chunk(s) of ${CHUNK_MB} MB — resuming any already-pushed parts"

# 2. Push each chunk with retries + resume
for i in $(seq 0 $((N - 1))); do
    src="$SPLIT_DIR/chunk_$(printf '%03d' "$i")"
    dst="$REMOTE_BASE.$(printf '%03d' "$i")"
    local_size=$(stat -c%s "$src")

    # Resume: skip chunks already fully on-device.
    remote_size=$(ADB shell "stat -c%s '$dst' 2>/dev/null" | tr -d '\r' || true)
    if [[ "$remote_size" == "$local_size" ]]; then
        echo "    chunk $((i + 1))/$N already complete — skipped"
        continue
    fi

    ok=0
    for attempt in $(seq 1 $MAX_TRIES); do
        if ADB push "$src" "$dst" >/dev/null 2>&1; then
            # Verify the chunk actually landed intact.
            remote_size=$(ADB shell "stat -c%s '$dst' 2>/dev/null" | tr -d '\r' || true)
            if [[ "$remote_size" == "$local_size" ]]; then
                ok=1
                echo "    chunk $((i + 1))/$N ok (attempt $attempt)"
                break
            fi
            ADB shell "rm -f '$dst'" >/dev/null 2>&1
        fi
        echo "    chunk $((i + 1))/$N failed (attempt $attempt/$MAX_TRIES) — reconnecting…"
        wait_for_device || true
    done
    if [[ $ok -ne 1 ]]; then
        echo "Chunk $((i + 1)) exhausted $MAX_TRIES retries; aborting (run again to resume)." >&2
        exit 1
    fi
done

# 3. Reassemble chunk-by-chunk (short commands survive flaky links better
#    than one long cat) + verify total size
echo "==> Reassembling on device…"
ADB shell "rm -f $REMOTE_BASE.apk" >/dev/null 2>&1
for i in $(seq 0 $((N - 1))); do
    part="$REMOTE_BASE.$(printf '%03d' "$i")"
    ok=0
    for attempt in $(seq 1 $MAX_TRIES); do
        if ADB shell "cat '$part' >> '$REMOTE_BASE.apk'" >/dev/null 2>&1; then
            ok=1
            break
        fi
        echo "    append $((i + 1))/$N failed (attempt $attempt) — reconnecting…"
        wait_for_device || true
    done
    if [[ $ok -ne 1 ]]; then
        echo "Append $((i + 1)) failed after $MAX_TRIES tries; aborting." >&2
        exit 1
    fi
done

local_bytes=$(stat -c%s "$APK")
remote_bytes=$(ADB shell "stat -c%s '$REMOTE_BASE.apk' 2>/dev/null" | tr -d '\r' || true)
if [[ "$local_bytes" != "$remote_bytes" ]]; then
    echo "Size mismatch (local=$local_bytes remote=$remote_bytes); aborting." >&2
    ADB shell "rm -f '$REMOTE_BASE.apk'" >/dev/null 2>&1
    exit 1
fi
# NOTE: '.???' would ALSO match '.apk' — the very file we just assembled!
# Chunk extensions all start with '0' (000…028), so anchor on that.
ADB shell "rm -f $REMOTE_BASE.0*" >/dev/null 2>&1 || true

echo "==> Installing…"
if ADB shell pm install -r "$REMOTE_BASE.apk"; then
    ADB shell rm -f "$REMOTE_BASE.apk" >/dev/null 2>&1
    echo "==> SUCCESS"
else
    # Keep the assembled APK so install can be retried without re-transfer.
    echo "pm install failed — assembled APK kept at $REMOTE_BASE.apk" >&2
    echo "  retry: adb shell pm install -r $REMOTE_BASE.apk" >&2
    exit 1
fi
