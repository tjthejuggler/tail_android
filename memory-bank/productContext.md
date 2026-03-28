# Product Context — Tail

**Last updated:** 2026-03-28T15:47Z

## Why This Product Exists

Tail replaces a Tasker-based habit tracking workflow on Android. The user has a comprehensive habit tracking system spanning desktop (PyQt widget) and mobile, with 76+ habits tracked daily. The Android app provides a native, fast, and reliable interface for incrementing habits throughout the day.

## Problems It Solves

1. **Tasker limitations** — The previous Tasker-based system was fragile and hard to maintain
2. **Cross-platform data sharing** — Shares the same JSON files with the desktop PyQt widget via Syncthing
3. **Quick habit logging** — Single tap to increment, with visual feedback via color changes
4. **Historical tracking** — Streaks, anti-streaks, all-time highs, rolling averages
5. **Text logging** — Some habits support free-text entries (e.g., book titles, dream journals)
6. **Inter-app communication** — Other personal apps (Wags) can trigger habit increments

## How It Should Work

1. User opens app → sees 8×10 grid of habit buttons with icons
2. Tap a habit → count increments by 1, button color changes immediately
3. Long-press → opens custom input dialog (for habits configured for it)
4. Navigate between days using arrow buttons in the top bar
5. Settings screen for file configuration, per-habit toggles, screen management
6. App Stats screen for detailed analytics (rolling averages, all-time highs, graphs)
7. Share sheet integration — highlight text anywhere → share to Tail → pick a text-input habit

## User Experience Goals

- **Instant feedback** — Button recolors on the same frame as tap (zero-cost targeted update)
- **Background processing** — Full stat recalculation and file writes happen off main thread
- **Debounced reorder** — Rapid taps accumulate into single moves with instant visual feedback
- **Dark theme** — Always-on dark theme for comfortable use
- **Minimal friction** — One tap to log most habits, no unnecessary confirmations
