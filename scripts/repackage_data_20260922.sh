#!/usr/bin/env bash
# Phase 1b — repackage core-data data/ clusters (2026-09-22).
# Conservative: moves only self-contained clusters; habit-core stays in data/.
set -euo pipefail
cd /home/twain/AndroidStudioProjects/tail
D=core-data/src/main/java/com/example/tail/data

move() { mkdir -p "$D/$2"; git mv "$D/$1.kt" "$D/$2/$1.kt"; perl -pi -e "s/^package com\\.example\\.tail\\.data\$/package com.example.tail.data.$2/" "$D/$2/$1.kt"; }

# chess
for f in ChessComRepository ChessComService ChessFreeplaySettlement ChessPuzzleRushSession \
         ChessReadinessCorrelations ChessReadinessEngine ChessReadinessStatsCalculator \
         ChessReadinessV2StatsCalculator ChessReflexStatsCalculator ChessRollingWindow; do move "$f" chess; done
# movie
for f in MovieBridgeService MovieCacheStore OmdbService ImdbRatingCache; do move "$f" movie; done
# health
for f in GarminRepository GarminService SleepDataRepository; do move "$f" health; done
# media
for f in ItunesMusicLookup SpotifyTrackIdCache MediaLibraryRepository MediaTextLogBootstrapper; do move "$f" media; done
# location
move LocationRepository location

echo "moved; rewriting FQNs and imports (longest first)..."
mapfile -t NAMES < <(find "$D" -maxdepth 2 -name '*.kt' -printf '%f\n' | sed 's/\.kt$//' | awk '{print length, $0}' | sort -rn | awk '{print $2}')
for name in "${NAMES[@]}"; do
  sub=$(find "$D" -maxdepth 2 -name "$name.kt" -printf '%h\n' | sed "s|$D/||" | head -1)
  [ "$sub" = "$name.kt" ] && continue   # still in data root
  grep -rl --include='*.kt' "com\\.example\\.tail\\.data\\.$name\\b" app/src core-data/src 2>/dev/null | while read -r tgt; do
    perl -pi -e "s/\\bcom\\.example\\.tail\\.data\\.$name\\b/com.example.tail.data.$sub.$name/g" "$tgt"
  done
done
echo "core-data repackage complete"
