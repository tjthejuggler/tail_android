#!/usr/bin/env bash
# restic_habitsdb.sh — deduplicating GFS backup of the synced habits DB.
#
# WHY (2026-10-19): habitsdb.txt grows forever (~5 MB and climbing) and the
# bulk of it is ancient, immutable history (some entries date back to 2004).
# Copy-per-backup schemes re-store all of it every time; syncthing itself is
# REPLICATION, not backup (it propagates deletes/corruption — the 2026-10-04
# mass timestamp wipe). restic chunks the file content-addressably: unchanged
# data (the ancient part) is stored exactly once, and each snapshot only adds
# the few KB that actually changed. Every snapshot still restores the FULL
# database — point-in-time complete backups at incremental cost.
#
# Retention (GFS / grandfather-father-son):
#   keep  7 daily,  5 weekly,  12 monthly,  3 yearly snapshots, then prune.
# Repo + credentials come from restic_habitsdb.env (loaded by the supervisor,
# or sourced below when run by hand).
#
# Run by hand:  bash scripts/backup/restic_habitsdb.sh
# Scheduled:    supervisor service "habitsdb-restic-backup" (every 2 h).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Standalone invocation: load the same env the supervisor injects.
if [[ -z "${RESTIC_REPOSITORY:-}" ]]; then
    # shellcheck disable=SC1091
    set -a              # export everything the env file defines
    source "$SCRIPT_DIR/restic_habitsdb.env"
    set +a
fi

# restic lives in ~/.local/bin (static binary, no sudo needed).
export PATH="$HOME/.local/bin:$PATH"

BACKUP_SRC="${HABITSDB_BACKUP_SRC:-/home/twain/habitsdb}"

# One-write lock: restic handles concurrent runs safely, but a hung run plus
# the supervisor's next tick would pile up. flock -n skips instead.
exec 9>"${RESTIC_LOCK_FILE:-/tmp/restic_habitsdb.lock}"
if ! flock -n 9; then
    echo "restic_habitsdb: another run holds the lock — skipping." >&2
    exit 0
fi

# Initialise the repository on first ever run (idempotent thereafter).
if ! restic snapshots --quiet >/dev/null 2>&1; then
    echo "restic_habitsdb: initialising repository $RESTIC_REPOSITORY"
    restic init
fi

# Snapshot the habits DB and its sibling copies (habitsdb.txt, the
# .bak_*/sync-conflict text files — near-duplicates that dedupe to almost
# nothing and give extra restore points). The heavy subfolders are EXCLUDED:
# Auto_backups/ is 12 GB of the phone's own on-device snapshot history
# (scanning it every run wastes minutes and it grows forever), and
# Wags_debug//Skintracker_backup/ are other apps' data with their own
# regimes. restic's GFS retention replaces all of it with a few-KB-per-run
# point-in-time snapshot of the CURRENT state.
restic backup "$BACKUP_SRC" \
    --tag habitsdb \
    --exclude '*.tmp' \
    --exclude '.stfolder' \
    --exclude '.stversions' \
    --exclude "$BACKUP_SRC/Auto_backups" \
    --exclude "$BACKUP_SRC/Wags_debug" \
    --exclude "$BACKUP_SRC/Skintracker_backup"

# GFS retention + prune. --prune repacks only what the kept snapshots no
# longer reference; with dedup the repo stays roughly the size of one full
# DB plus a few weeks of real changes.
restic forget \
    --keep-daily   7 \
    --keep-weekly  5 \
    --keep-monthly 12 \
    --keep-yearly  3 \
    --prune

# Integrity check on Sundays only (cheap read-data check; full pack check
# monthly keeps this quick on a small repo).
DOW="$(date +%u)"
if [[ "$DOW" == "7" ]]; then
    DOM="$(date +%-d)"
    if (( DOM <= 7 )); then
        restic check --read-data-subset=10%
    else
        restic check
    fi
fi

echo "restic_habitsdb: done  $(date '+%F %T')"
