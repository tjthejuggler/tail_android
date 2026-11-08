# Blood Test Habit Type — Design Document

*Created: 2026-11-08*

## Overview

A **blood test habit** is a special habit type that occupies ONE square in the habit grid but
backs it with a large structured item database. Any kind of blood test (ketone/glucose, nutrient
panels, full blood panel, hormones — anything) can be logged, with any number of items per test.
Items enter the database two ways:

1. **Manual entry** — a form listing every item ever measured before (pre-filled names/units,
   blank values), plus inline add-new-item.
2. **AI parsing** — paste (or share into) a free-text description or a shared lab-result
   document's text; the configured LLM extracts a structured item list which is previewed,
   editable, then saved.

The habit's daily count in `habitsdb.txt` equals the **number of test items measured that day**
(full panels → big number, single glucose reading → 1–2). That count is the default graph metric.

---

## 1. Data model

### Item store (internal storage, MealLogRepository pattern)

`files/blood_tests/<sanitised-habit-name>.json` — JSON array of entries:

```json
[
  {
    "id": "uuid",
    "date": "2026-11-08",
    "time": "09:30",
    "source": "manual | ai",
    "rawText": "optional original text the AI parsed",
    "items": [
      { "name": "Glucose", "value": 92.0, "unit": "mg/dL", "range": "70-99", "flag": "normal" },
      { "name": "Vitamin D", "value": 21.5, "unit": "ng/mL", "range": "30-100", "flag": "low" }
    ]
  }
]
```

- `BloodTestItem`: name, value (Double), unit?, reference range?, flag? (low/high/normal).
- `BloodTestEntry`: one lab draw / pasted report. Multiple entries per day allowed (re-draws,
  follow-up panels) — the daily count is the SUM of item counts.
- Repos: `core-data/.../data/bloodtest/BloodTestRepository.kt` (CRUD + aggregations),
  `BloodTestAiParser.kt` (one-shot LLM extraction via the existing `AiAssistantConfig`
  endpoints, JSON-only response, no tool loop).

### Settings (`AppSettings`)

- `bloodTestHabits: Set<String>` — habits with the type enabled (pill-log set pattern).
- No new graph keys: item selection reuses the existing `graphMetricSelection` map.

## 2. Graph integration

Metric keys (prefixed so they can never collide with habit-level metric keys):

- `blood:__count` — "Items tested" (default; daily item count).
- `blood:<ItemName>` — one series per item; value = latest measurement of that item that day,
  stored ×100 in `GraphDataPoint.bloodTestValues` (weights/environment tenths pattern).

- `getAvailableMetrics` special-cases blood habits: count metric + the currently-selected item
  metrics.
- `getSelectedMetrics` defaults to `{blood:__count}`.
- `GraphMetricToggleRow` shows a **🩸 Items…** button for blood habits opening
  `BloodTestItemFilterPopup` — a searchable multi-select checkbox list of EVERY item ever
  measured (the popup filter menu). Selection persists through `graphMetricSelection`.
- Decimal formatting: item values render with 2 decimals (×100 scaling).

## 3. Habit grid integration

- Tap branch in `onHabitClick` (before text-input) opens `BloodTestDialog`.
- Edit-mode panel: "Blood test" toggle in SPECIAL HABIT TYPES (pill-log toggle pattern).
- Saving an entry appends to the store and increments the habit by the item count
  (`incrementHabit`, one timestamp).

## 4. Files

New: `BloodTestModels.kt`, `BloodTestRepository.kt`, `BloodTestAiParser.kt` (core-data),
`HabitViewModelBloodTest.kt`, `ui/grid/BloodTestDialog.kt`, `ui/stats/BloodTestGraphFilter.kt`.

Modified: `HabitModels.kt`, `SettingsRepository.kt`, `HabitViewModel.kt` (repo member +
GraphDataPoint field + getGraphData branch), `HabitViewModelGraphs.kt`, `GraphsScreen.kt`,
`HabitGridScreen.kt`, `HabitGridEditSections.kt`, `HabitGridScreenSections.kt`,
`HabitGridEditBarHost.kt`.
