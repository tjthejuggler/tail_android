#!/usr/bin/env bash
# Phase 5 guardrail (2026-09-22) — keep files and packages tractable.
# Run before commits:  bash scripts/check_file_size.sh
#
# Baseline mechanism (grandfathering): files/packages that were already over
# the limit at the 2026-09-22 reorganization are listed in
#   scripts/file_size_baseline.txt        (file:lines per oversized file)
#   scripts/package_size_baseline.txt     (path:count per oversized package)
# A baseline FILE fails only if it GROWS by more than GROWTH_ALLOWANCE lines.
# A baseline PACKAGE fails only if it GAINS more than GROWTH_PKG_ALLOWANCE files.
# Anything NOT in the baseline fails immediately. Delete a baseline line when
# you split its file/package.
set -uo pipefail
cd "$(dirname "$0")/.."

SOFT_LINES=600
HARD_LINES=900
HARD_PKG_FILES=20
GROWTH_ALLOWANCE=50
GROWTH_PKG_ALLOWANCE=2
FILE_BASELINE=scripts/file_size_baseline.txt
PKG_BASELINE=scripts/package_size_baseline.txt

fail=0

declare -A base_lines
[ -f "$FILE_BASELINE" ] && while IFS=: read -r path lines; do
  [ -n "${path:-}" ] && base_lines["$path"]="$lines"
done < "$FILE_BASELINE"

declare -A base_pkgs
[ -f "$PKG_BASELINE" ] && while IFS=: read -r path count; do
  [ -n "${path:-}" ] && base_pkgs["$path"]="$count"
done < "$PKG_BASELINE"

echo "── Kotlin files over ${HARD_LINES} lines (new/grown = FAIL, baseline = grandfathered):"
over=$(mktemp)
find app/src core-data/src -name '*.kt' | while read -r f; do
  lines=$(wc -l < "$f")
  [ "$lines" -gt "$HARD_LINES" ] && printf '%s:%s\n' "$f" "$lines"
done | sort > "$over"
new=0
while IFS=: read -r f lines; do
  if [ -n "${base_lines[$f]:-}" ]; then
    growth=$(( lines - base_lines[$f] ))
    if [ "$growth" -gt "$GROWTH_ALLOWANCE" ]; then
      printf '  FAIL (grew %d lines) %6d  %s\n' "$growth" "$lines" "$f"; fail=1
    fi
  else
    printf '  FAIL (new) %6d  %s\n' "$lines" "$f"; fail=1
    new=$((new+1))
  fi
done < "$over"
total=$(wc -l < "$over")
grand=$(( total - new ))
echo "  $total oversized file(s): $grand grandfathered, $new new"

echo "── Kotlin files over ${SOFT_LINES} lines (soft limit, advisory):"
find app/src core-data/src -name '*.kt' | while read -r f; do
  lines=$(wc -l < "$f")
  if [ "$lines" -gt "$SOFT_LINES" ] && [ "$lines" -le "$HARD_LINES" ]; then
    printf '  warn %6d  %s\n' "$lines" "$f"
  fi
done
echo "  (advisory — split these when you next touch them)"

echo "── Packages over ${HARD_PKG_FILES} Kotlin files (new/grown = FAIL, baseline = grandfathered):"
pkgover=$(mktemp)
find app/src/main/java core-data/src/main/java -mindepth 1 -type d | while read -r d; do
  n=$(find "$d" -maxdepth 1 -name '*.kt' | wc -l)
  [ "$n" -gt "$HARD_PKG_FILES" ] && printf '%s:%s\n' "$d" "$n"
done | sort > "$pkgover"
if [ -s "$pkgover" ]; then
  while IFS=: read -r d n; do
    if [ -n "${base_pkgs[$d]:-}" ]; then
      growth=$(( n - base_pkgs[$d] ))
      if [ "$growth" -gt "$GROWTH_PKG_ALLOWANCE" ]; then
        printf '  FAIL (grew by %d files) %4d files  %s\n' "$growth" "$n" "$d"; fail=1
      fi
    else
      printf '  FAIL (new) %4d files  %s\n' "$n" "$d"; fail=1
    fi
  done < "$pkgover"
else
  echo "  none"
fi

rm -f "$over" "$pkgover"
if [ "$fail" -eq 1 ]; then
  echo "check_file_size: FAILED — split the offending files/packages (see plans/codebase_reorganization_plan.md)."
  exit 1
fi
echo "check_file_size: OK"
