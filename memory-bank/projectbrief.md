# Project Brief — Tail

**Last updated:** 2026-03-28T15:47Z

## Overview

Tail is a native Android habit tracking app built with Kotlin + Jetpack Compose. It maintains full data compatibility with a desktop PyQt widget system (`py_habits_widget`) by sharing the same `habitsdb_phone.txt` JSON file via Syncthing.

## Core Purpose

Replace Tasker-based habit tracking workflows with a dedicated Android app that:
- Displays 76+ habits in an 8×10 grid matching the desktop layout
- Color-codes buttons based on today's count (7 tiers: red → orange → green → blue → pink → yellow → glass)
- Reads/writes the same JSON data files as the desktop PyQt widget
- Supports historical data merging for streak/ATH calculations

## Key Requirements

1. **Data compatibility** — Must read/write `habitsdb_phone.txt` in the exact JSON format used by the desktop system
2. **Grid layout** — 8 columns × 10 rows, 76 habits in exact order matching desktop
3. **Color-coded icons** — 269 PNG icons tinted white, with 7 color tiers based on daily count
4. **Corner stats** — All-time high, streak/antistreak, longest streak displayed on each button
5. **Custom input mode** — Some habits use numeric dialogs instead of simple +1
6. **SAF file access** — Pick files from any location with persistent URI permission
7. **IPC API** — ContentProvider + BroadcastReceiver for same-keystore app integration (used by companion app "Wags")
8. **Multiple screens** — Named screens/pages of habits with configurable layouts
9. **Dark theme** by default
10. **Edit mode** — Reorder, delete, change icons, configure conditional habits

## Companion Projects

- **py_habits_widget** — Desktop PyQt widget (shares data files)
- **Wags** — Companion Android app (uses IPC API to read habits and send increments)
- **Syncthing** — File sync between phone and desktop
- **syncthing_conflict_resolver** — Python script resolving Syncthing conflicts in habit DB files

## Target Users

Single user (personal habit tracking system spanning desktop and mobile).
