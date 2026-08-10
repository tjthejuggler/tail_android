# Secondary Value Feature — Wags Integration Spec

## Overview

Tail now supports a **Secondary Value** for habits. This allows a habit to track
two independent values per day:

- **Value 1 (Primary):** The normal habit count (stored under the habit name key)
- **Value 2 (Secondary):** An additional value (stored under `secondary_value:<habitName>`)

For **Meditations**, the mapping is:
- **Value 1 = minutes** meditated
- **Value 2 = session count**

## Storage Format

Both values live in the same `habitsdb.txt` JSON file, which is a
`Map<String, Map<String, Int>>` (habitName → date → count).

```
{
  "Meditations": {
    "2024-01-15": 20,     ← minutes (Value 1)
    "2024-01-16": 15,
    ...
  },
  "secondary_value:Meditations": {
    "2024-01-15": 1,      ← session count (Value 2)
    "2024-01-16": 1,
    ...
  }
}
```

### Key Convention

| Slot             | Key in habitsdb.txt              |
|------------------|----------------------------------|
| Primary (Value 1)| `<habitName>`                    |
| Secondary (Value 2) | `secondary_value:<habitName>` |

### Important Rules

1. **`secondary_value:` keys are NOT habits.** They must be filtered out
   anywhere the app iterates over habit names (stats, grids, point totals).
   Tail already does this via `isSecondaryValueKey()`.

2. **Merge strategy:** Always use `max(existing, new)` when writing from
   external sources (Wags, Python scripts). This prevents data loss from
   race conditions with Syncthing sync.

3. **Renaming:** When a habit is renamed, the `secondary_value:` key must also
   be renamed. Tail handles this automatically in `renameHabit()`.

## What Wags Needs To Do

### For Meditation Sync

When Wags syncs meditation data to the habits DB, it should write:

1. **Minutes** → `"Meditations"` key (primary slot)
   - Each date: `db["Meditations"]["2024-01-15"] = 20`

2. **Session count** → `"secondary_value:Meditations"` key (secondary slot)
   - Each date: `db["secondary_value:Meditations"]["2024-01-15"] = 1`

### Implementation Steps for Wags

1. **When writing meditation data to habitsdb.txt (or habitsdb_phone.txt):**

```python
# Minutes → primary
if "Meditations" not in db:
    db["Meditations"] = {}
for date_str, minutes in wags_meditation_minutes.items():
    existing = db["Meditations"].get(date_str, 0)
    db["Meditations"][date_str] = max(existing, minutes)

# Sessions → secondary
sec_key = "secondary_value:Meditations"
if sec_key not in db:
    db[sec_key] = {}
for date_str, sessions in wags_meditation_sessions.items():
    existing = db[sec_key].get(date_str, 0)
    db[sec_key][date_str] = max(existing, sessions)
```

2. **If Wags currently writes session counts to `"Meditations"`:**
   - Stop doing that for dates where you have minutes data
   - Move session counts to the `secondary_value:Meditations` key
   - Write minutes to the `Meditations` key instead

3. **If Wags only has minutes (no session count):**
   - Write minutes to `"Meditations"` (primary)
   - Leave `"secondary_value:Meditations"` empty for those dates
   - That's fine — the secondary slot is optional per-date

### Generalizing to Other Habits

The secondary value feature is not meditation-specific. Any habit can have it
enabled (via the edit screen toggle in Tail). The convention is always:

```
secondary_value:<exact habit name as it appears in habitsdb.txt>
```

If Wags ever needs to sync a secondary value for another habit, use the same
pattern.

## Graph Display

In Tail's graph mode, when a habit has secondary values enabled, three buttons
appear:

| Button   | Mode | Shows                          |
|----------|------|--------------------------------|
| Points   | 0    | Calculated points per day      |
| Value 1  | 1    | Raw primary value (minutes)    |
| Value 2  | 2    | Secondary value (sessions)     |

## Summary for Wags Devs

**TL;DR:** Write meditation **minutes** to the `"Meditations"` key and meditation
**session counts** to the `"secondary_value:Meditations"` key in habitsdb.txt.
Use `max()` merge. The `secondary_value:` prefix is the universal convention
for secondary values in Tail.
