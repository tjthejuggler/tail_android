# Protocol v6 — Joint-Habit Two-Way Sync (Tail ⇄ Hoot)

**Date:** 2026-09-21
**Status:** IMPLEMENTED — both directions live
**Scope:** meal, pills, water (+ any text habit Hoot maps, e.g. electrolytes)

---

## 1. What this solves

Tail and Hoot share a set of "joint habits" — meal, pills, water,
electrolytes — mapped in Hoot's Settings → Tail integration. The contract:

> **A change to any joint habit in either app reflects in the other app for
> that day.**

Before protocol v6 only the Tail→Hoot direction existed (read API +
change feed). A meal captured in Hoot never reached Tail.

## 2. Tail → Hoot (pre-existing, unchanged)

1. Every Tail write calls [`TailChangeLog.noteChange`](../../core-data/src/main/java/com/example/tail/data/TailChangeLog.kt)
   → `ACTION_ENTRY_ADDED` broadcast + `/v2/changes` stamp + `notifyChange`.
2. Hoot's `TailSyncReceiver` receives it → `TailSyncManager.sync(BROADCAST)`.
3. The pull uses `?after=` incremental cursors; dedup via stable `entry_id`.

## 3. Hoot → Tail (new, protocol v6)

### 3.1 Transport

Same trust model as every other Tail integration: **manifest-declared
broadcast receivers guarded by the `com.example.tail.permission.
TAIL_INTEGRATION` signature permission** — only same-keystore apps can
deliver.

Two actions (handled by Tail's `app/src/main/java/com/example/tail/ipc/
CompanionEntryReceiver.kt`):

| Action | Extras | Tail-side effect |
|---|---|---|
| `com.example.tail.ACTION_ADD_TEXT_ENTRY` | `EXTRA_HABIT_NAME`, `EXTRA_TEXT`, `EXTRA_TIMESTAMP` (Long, event time), `EXTRA_ENTRY_ID` | Appends to the text habit's log **and** increments its daily count for the event's date — identical to an in-app entry |
| `com.example.tail.ACTION_ADD_MEAL_LOG` | `EXTRA_HABIT_NAME`, `EXTRA_MEAL_JSON`, `EXTRA_TIMESTAMP`, `EXTRA_ENTRY_ID` | Inserts a structured `MealLog` **and** increments the meal habit's daily count (honours `countedIncrement`) |

`EXTRA_MEAL_JSON` keys: `title`, `summary`, `calories`, `protein_grams`,
`carbs_grams`, `fat_grams`, `ingredients` (JSON array of strings),
`is_vegan`, `health_notes`, `transcript`, `is_manual`.

### 3.2 Echo contract (why no duplicates)

Every push carries a fresh `hoot:<uuid>` `EXTRA_ENTRY_ID`.

- **Meals:** Tail preserves the id as `MealLog.id`; the v2 provider surfaces
  it as `entry_id`. Hoot's pull skips `hoot:`-prefixed rows — reinstall-proof,
  no registry needed.
- **Text rows** (pills/water/misc): Tail's text log has no id slot; the echo
  is recognised by the **(habit, second-truncated event timestamp)** pair in
  Hoot's `EchoRegistry` (persisted in `files/tail_push_registry.json` +
  in-memory set, capped at 2000, oldest evicted). Cursor still advances past
  echoes — each is visited exactly once.

### 3.3 Retry guard (Tail side)

`CompanionEchoRegistry` (Tail, `files/companion_echo_registry.json`, keyed
habit|entry-id|event-minute) records each write BEFORE it happens; a
re-delivered broadcast is a no-op, never a double append.

### 3.4 Multi-item semantics

Supplements pushed as **one newline-joined entry** — Tail's own multi-item
"Took Pills" convention. Tail applies ONE count increment; on the echo Hoot's
`SupplementListSplitter` splits it back into the same per-item rows 1:1.
(Per-item pushes would collide on Tail's second-precision text-log keys and
over-count.)

### 3.5 Multi-target habits

The mapped habit must exist and be of the right kind on the Tail side:
text habits need a text log (`settings.textInputFileUris[habit]`), meal
habits need the meal type enabled. Otherwise the write is ignored with a
log line (Tail never crashes on a stale mapping).

## 4. Where things live

| Piece | File |
|---|---|
| Tail receiver + retry registry | `app/src/main/java/com/example/tail/ipc/CompanionEntryReceiver.kt` |
| Tail manifest registration | `app/src/main/AndroidManifest.xml` (Protocol v6 block) |
| Hoot sender | `../Hoot/app/src/main/java/com/example/hoot/data/tail/TailPushClient.kt` |
| Hoot capture integration | `../Hoot/.../data/intake/IntakeCaptureService.kt` (+ AddMealScreen / AddSupplementScreen manual paths) |
| Hoot echo filters | `../Hoot/.../data/tail/TailSyncManager.kt` (all four entity mappers) |
