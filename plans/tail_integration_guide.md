# Tail Integration Guide
## How to Hook Another App into Tail's Habit Incrementing System

**Last updated:** 2026-03-14  
**Applies to:** Tail app package `com.example.tail`, minSdk 26+

---

## Overview

Tail exposes two IPC (Inter-Process Communication) endpoints that any app you own can use:

1. **ContentProvider** — Read the full list of habits (names and indices) from Tail at runtime
2. **BroadcastReceiver** — Tell Tail to increment a specific habit's count for today by 1

Together these let your other app build a settings screen where the user picks which of their app's events maps to which Tail habit, and then fires increments automatically when those events occur.

The entire system is locked behind a **`signature`-level Android permission**, meaning Android will only allow the communication if both apps are signed with the exact same keystore. No other app on the device can intercept or spoof these calls.

---

## Part 1 — Security Model (Read This First)

### The Permission

Tail declares a custom permission in its `AndroidManifest.xml`:

```xml
<permission
    android:name="com.example.tail.permission.TAIL_INTEGRATION"
    android:protectionLevel="signature"
    android:label="Tail Integration" />
```

`protectionLevel="signature"` means Android will only grant this permission to apps that share the same signing certificate as the app that declared it. You do not need to ask the user for anything — it is granted automatically at install time if the keystores match, and silently denied if they don't.

### What Your App Must Declare

In your other app's `AndroidManifest.xml`, add this single line inside `<manifest>` (not inside `<application>`):

```xml
<uses-permission android:name="com.example.tail.permission.TAIL_INTEGRATION" />
```

That's the only manifest change required in your app. Android checks the signature at install time. If the keystores match, the permission is granted and both IPC endpoints work. If they don't match, all calls silently fail or throw a `SecurityException`.

### Why Tail Also Holds Its Own Permission

Tail's manifest also contains:
```xml
<uses-permission android:name="com.example.tail.permission.TAIL_INTEGRATION" />
```

This is not a mistake. When your app calls `sendBroadcast(intent, "com.example.tail.permission.TAIL_INTEGRATION")`, the second argument is a *receiver permission* — it tells Android "only deliver this broadcast to receivers that **hold** this permission." Since Tail declares the permission, it must also explicitly hold it via `<uses-permission>` or Android won't deliver the broadcast to it. This is a quirk of Android's permission system for self-declared signature permissions.

---

## Part 2 — Reading the Habit List (ContentProvider)

Before your settings screen can let the user pick a habit, it needs to know what habits exist in Tail. The ContentProvider gives you that list.

### Endpoint Details

| Property | Value |
|----------|-------|
| Authority | `com.example.tail.provider` |
| Full URI | `content://com.example.tail.provider/habits` |
| Column: `habit_id` | `Int` — 0-based index in the current display order |
| Column: `habit_name` | `String` — exact habit name as stored in the JSON database |
| Supported operations | `query()` only — insert/update/delete throw `UnsupportedOperationException` |

The list returned reflects Tail's **current active screen order**. If the user has set up named screens in Tail, all screens are flattened into one ordered list. If no custom order is set, the canonical default order of 76 habits is returned.

### Kotlin Code — Querying the Habit List

```kotlin
import android.content.Context
import android.net.Uri

data class TailHabit(val id: Int, val name: String)

fun fetchTailHabits(context: Context): List<TailHabit> {
    val uri = Uri.parse("content://com.example.tail.provider/habits")
    val habits = mutableListOf<TailHabit>()

    try {
        val cursor = context.contentResolver.query(
            uri,
            null,   // null projection = return all columns
            null,   // no selection filter
            null,   // no selection args
            null    // no sort order (provider returns its own order)
        )
        cursor?.use {
            while (it.moveToNext()) {
                val id   = it.getInt(it.getColumnIndexOrThrow("habit_id"))
                val name = it.getString(it.getColumnIndexOrThrow("habit_name"))
                habits.add(TailHabit(id, name))
            }
        }
    } catch (e: SecurityException) {
        // Keystores don't match — permission was not granted
        // Log or show an error to the user
    } catch (e: Exception) {
        // Tail is not installed, or some other error
    }

    return habits
}
```

> **Threading:** `contentResolver.query()` is a blocking call. Always run it on a background thread (e.g., inside a `withContext(Dispatchers.IO)` coroutine block or a `ViewModel` launched coroutine). Never call it on the main thread.

### Kotlin Code — Coroutine-Safe Version

```kotlin
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TailHabit(val id: Int, val name: String)

suspend fun fetchTailHabits(context: Context): List<TailHabit> =
    withContext(Dispatchers.IO) {
        val uri = Uri.parse("content://com.example.tail.provider/habits")
        val habits = mutableListOf<TailHabit>()
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id   = cursor.getInt(cursor.getColumnIndexOrThrow("habit_id"))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow("habit_name"))
                    habits.add(TailHabit(id, name))
                }
            }
        } catch (_: Exception) { /* Tail not installed or permission denied */ }
        habits
    }
```

### When to Query

- Query once when your settings screen opens, to populate the habit picker dropdown/list
- Do **not** cache the result long-term — the user may reorder or rename habits in Tail
- You do not need to query before sending an increment broadcast; the broadcast uses the name or index you already stored in your settings

---

## Part 3 — Incrementing a Habit (BroadcastReceiver)

When an event occurs in your app that you want to count in Tail, send a broadcast. Tail's `HabitIncrementReceiver` will receive it, look up the habit, and add 1 to today's count in `habitsdb_phone.txt`.

### Endpoint Details

| Property | Value |
|----------|-------|
| Action | `com.example.tail.ACTION_INCREMENT_HABIT` |
| Extra key | `EXTRA_HABIT_ID` |
| Extra value (preferred) | `String` — the exact habit name (e.g. `"Flossed"`) |
| Extra value (alternative) | `Int` — 0-based index in the current display order |
| Target package | `com.example.tail` (must be set explicitly) |
| Receiver permission arg | `"com.example.tail.permission.TAIL_INTEGRATION"` |

**Always prefer sending by name.** Indices can shift if the user reorders habits in Tail. Names are stable as long as the habit exists.

### Kotlin Code — Sending the Increment Broadcast

```kotlin
import android.content.Context
import android.content.Intent

fun incrementTailHabit(context: Context, habitName: String) {
    val intent = Intent("com.example.tail.ACTION_INCREMENT_HABIT").apply {
        // Explicit package target — required on Android 8+ for implicit broadcasts
        setPackage("com.example.tail")
        putExtra("EXTRA_HABIT_ID", habitName)   // String: habit name
    }
    // The second argument restricts delivery to receivers that HOLD the permission.
    // This is defence-in-depth: even if another app somehow registered a receiver
    // for this action, it won't receive the broadcast unless it also holds the permission.
    context.sendBroadcast(intent, "com.example.tail.permission.TAIL_INTEGRATION")
}
```

### Kotlin Code — Sending by Index (if you must)

```kotlin
fun incrementTailHabitByIndex(context: Context, habitIndex: Int) {
    val intent = Intent("com.example.tail.ACTION_INCREMENT_HABIT").apply {
        setPackage("com.example.tail")
        putExtra("EXTRA_HABIT_ID", habitIndex)  // Int: 0-based index
    }
    context.sendBroadcast(intent, "com.example.tail.permission.TAIL_INTEGRATION")
}
```

### What Happens Inside Tail

When the broadcast arrives, `HabitIncrementReceiver.onReceive()`:

1. Calls `goAsync()` so it can do I/O without being killed after `onReceive()` returns
2. Reads Tail's `SettingsRepository` to get the SAF URI of `habitsdb_phone.txt`
3. If the extra was an Int, resolves it to a habit name using the current screen/order settings
4. Calls `HabitsRepository.incrementHabit(uri, context, habitName, 1)` which does an atomic read-modify-write on the JSON file for today's date
5. Logs success or failure to Logcat under the tag `HabitIncrementReceiver`

The increment always applies to **today's date** (`LocalDate.now()` in Tail's timezone). There is no way to increment a past date via the broadcast API.

---

## Part 4 — Building the Settings Screen in Your App

This is the recommended pattern for giving users control over which of your app's events map to which Tail habits.

### Concept

Your app stores a mapping: `YourEventKey → TailHabitName (String)`. When an event fires, you look up the mapped habit name and send the broadcast. The settings screen lets the user view and change these mappings.

### Step 1 — Store the Mapping

Use `SharedPreferences` or `DataStore` in your app to persist the mapping. Example using `SharedPreferences`:

```kotlin
object TailMappingPrefs {
    private const val PREFS_NAME = "tail_habit_mappings"

    fun saveMapping(context: Context, eventKey: String, habitName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(eventKey, habitName)
            .apply()
    }

    fun getMapping(context: Context, eventKey: String): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(eventKey, null)
    }

    fun clearMapping(context: Context, eventKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(eventKey)
            .apply()
    }
}
```

### Step 2 — Define Your App's Events

Create a list of the events in your app that can trigger habit increments. These are whatever makes sense for your app — button presses, completed actions, detected states, etc.

```kotlin
data class AppEvent(
    val key: String,          // stable internal key, never shown to user
    val displayName: String   // human-readable label shown in settings
)

// Example for a hypothetical app:
val MY_APP_EVENTS = listOf(
    AppEvent("walk_completed",    "Walk Completed"),
    AppEvent("meal_logged",       "Meal Logged"),
    AppEvent("water_drunk",       "Water Glass Drunk"),
    AppEvent("meditation_done",   "Meditation Session Done"),
    AppEvent("book_page_read",    "Book Page Read")
)
```

### Step 3 — Settings Screen (Jetpack Compose)

```kotlin
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TailIntegrationSettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Habit list fetched from Tail
    var tailHabits by remember { mutableStateOf<List<TailHabit>>(emptyList()) }
    var tailLoadError by remember { mutableStateOf(false) }

    // Current mappings: eventKey → habitName
    var mappings by remember {
        mutableStateOf(
            MY_APP_EVENTS.associate { event ->
                event.key to (TailMappingPrefs.getMapping(context, event.key) ?: "")
            }
        )
    }

    // Load habit list from Tail when screen opens
    LaunchedEffect(Unit) {
        val habits = fetchTailHabits(context)
        if (habits.isEmpty()) {
            tailLoadError = true
        } else {
            tailHabits = habits
            tailLoadError = false
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Tail Habit Integration",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Choose which Tail habit to increment when each event occurs.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (tailLoadError) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "Could not load habits from Tail. Make sure Tail is installed and both apps are signed with the same keystore.",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(MY_APP_EVENTS) { event ->
                EventToHabitRow(
                    event = event,
                    tailHabits = tailHabits,
                    selectedHabitName = mappings[event.key] ?: "",
                    onHabitSelected = { habitName ->
                        // Save to prefs
                        if (habitName.isEmpty()) {
                            TailMappingPrefs.clearMapping(context, event.key)
                        } else {
                            TailMappingPrefs.saveMapping(context, event.key, habitName)
                        }
                        // Update local state
                        mappings = mappings.toMutableMap().also { it[event.key] = habitName }
                    }
                )
            }
        }
    }
}

@Composable
fun EventToHabitRow(
    event: AppEvent,
    tailHabits: List<TailHabit>,
    selectedHabitName: String,
    onHabitSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = event.displayName,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "→ Tail habit:",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(90.dp)
                )
                Box {
                    OutlinedButton(
                        onClick = { expanded = true },
                        enabled = tailHabits.isNotEmpty()
                    ) {
                        Text(
                            text = if (selectedHabitName.isEmpty()) "(none)" else selectedHabitName,
                            maxLines = 1
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        // "None" option to clear the mapping
                        DropdownMenuItem(
                            text = { Text("(none — disable)") },
                            onClick = {
                                onHabitSelected("")
                                expanded = false
                            }
                        )
                        HorizontalDivider()
                        tailHabits.forEach { habit ->
                            DropdownMenuItem(
                                text = { Text(habit.name) },
                                onClick = {
                                    onHabitSelected(habit.name)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
```

### Step 4 — Firing the Increment When an Event Occurs

Wherever in your app the event happens, look up the mapped habit name and send the broadcast:

```kotlin
fun onWalkCompleted(context: Context) {
    val habitName = TailMappingPrefs.getMapping(context, "walk_completed")
    if (!habitName.isNullOrEmpty()) {
        incrementTailHabit(context, habitName)
    }
}

fun onMealLogged(context: Context) {
    val habitName = TailMappingPrefs.getMapping(context, "meal_logged")
    if (!habitName.isNullOrEmpty()) {
        incrementTailHabit(context, habitName)
    }
}
```

Or as a general dispatcher:

```kotlin
fun fireEventToTail(context: Context, eventKey: String) {
    val habitName = TailMappingPrefs.getMapping(context, eventKey)
    if (!habitName.isNullOrEmpty()) {
        incrementTailHabit(context, habitName)
    }
}

// Usage anywhere in your app:
fireEventToTail(context, "walk_completed")
fireEventToTail(context, "meal_logged")
```

---

## Part 5 — Complete Manifest for Your App

Here is the complete set of manifest additions your other app needs. Everything else in your manifest stays exactly as it is.

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.example.yourapp">

    <!-- ✅ ADD THIS: Request the Tail integration permission -->
    <!-- Android grants this automatically if both apps share the same keystore. -->
    <!-- No user prompt is shown. -->
    <uses-permission android:name="com.example.tail.permission.TAIL_INTEGRATION" />

    <application
        ... your existing application tag ...>

        ... your existing activities, services, etc. ...

    </application>
</manifest>
```

That is the **only** manifest change needed. No receiver, no provider, no service — just the one `<uses-permission>` line.

---

## Part 6 — Complete Self-Contained Helper File

Copy this single file into your app. It contains everything needed to interact with Tail.

```kotlin
// TailIntegration.kt
// Drop this file into your app. No other changes needed except the manifest permission.

package com.example.yourapp  // ← change to your app's package

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ─────────────────────────────────────────────────────────────────────────────
// Constants — do not change these; they are Tail's published API surface
// ─────────────────────────────────────────────────────────────────────────────

private const val TAIL_PACKAGE          = "com.example.tail"
private const val TAIL_PERMISSION       = "com.example.tail.permission.TAIL_INTEGRATION"
private const val TAIL_ACTION_INCREMENT = "com.example.tail.ACTION_INCREMENT_HABIT"
private const val TAIL_EXTRA_HABIT_ID   = "EXTRA_HABIT_ID"
private const val TAIL_PROVIDER_URI     = "content://com.example.tail.provider/habits"
private const val TAIL_COL_HABIT_ID     = "habit_id"
private const val TAIL_COL_HABIT_NAME   = "habit_name"

// ─────────────────────────────────────────────────────────────────────────────
// Data
// ─────────────────────────────────────────────────────────────────────────────

/** A single habit entry returned by [fetchTailHabits]. */
data class TailHabit(
    val id: Int,       // 0-based index in Tail's current display order
    val name: String   // exact habit name as stored in habitsdb_phone.txt
)

// ─────────────────────────────────────────────────────────────────────────────
// Read habit list
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Fetches the full list of habits from Tail via ContentProvider.
 *
 * Must be called from a background thread (or inside a coroutine with IO dispatcher).
 * Returns an empty list if Tail is not installed or the permission is not granted.
 */
suspend fun fetchTailHabits(context: Context): List<TailHabit> =
    withContext(Dispatchers.IO) {
        val uri = Uri.parse(TAIL_PROVIDER_URI)
        val habits = mutableListOf<TailHabit>()
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id   = cursor.getInt(cursor.getColumnIndexOrThrow(TAIL_COL_HABIT_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(TAIL_COL_HABIT_NAME))
                    habits.add(TailHabit(id, name))
                }
            }
        } catch (_: SecurityException) {
            // Keystores don't match — permission denied
        } catch (_: Exception) {
            // Tail not installed or provider unavailable
        }
        habits
    }

// ─────────────────────────────────────────────────────────────────────────────
// Increment a habit
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Tells Tail to increment [habitName]'s count for today by 1.
 *
 * Safe to call from any thread. The broadcast is fire-and-forget.
 * Does nothing if Tail is not installed or the permission is not granted.
 *
 * @param habitName The exact habit name as it appears in Tail (e.g. "Flossed").
 *                  Use [fetchTailHabits] to get the current list of valid names.
 */
fun incrementTailHabit(context: Context, habitName: String) {
    if (habitName.isBlank()) return
    val intent = Intent(TAIL_ACTION_INCREMENT).apply {
        setPackage(TAIL_PACKAGE)
        putExtra(TAIL_EXTRA_HABIT_ID, habitName)
    }
    try {
        context.sendBroadcast(intent, TAIL_PERMISSION)
    } catch (_: Exception) {
        // Tail not installed or permission denied — silently ignore
    }
}

/**
 * Tells Tail to increment the habit at [habitIndex] (0-based) for today by 1.
 *
 * Prefer [incrementTailHabit] by name when possible — indices shift if the user
 * reorders habits in Tail.
 */
fun incrementTailHabitByIndex(context: Context, habitIndex: Int) {
    val intent = Intent(TAIL_ACTION_INCREMENT).apply {
        setPackage(TAIL_PACKAGE)
        putExtra(TAIL_EXTRA_HABIT_ID, habitIndex)
    }
    try {
        context.sendBroadcast(intent, TAIL_PERMISSION)
    } catch (_: Exception) { }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mapping persistence
// ─────────────────────────────────────────────────────────────────────────────

private const val PREFS_NAME = "tail_habit_mappings"

/**
 * Saves a mapping from your app's [eventKey] to a Tail [habitName].
 * Pass an empty string for [habitName] to clear the mapping.
 */
fun saveTailMapping(context: Context, eventKey: String, habitName: String) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (habitName.isEmpty()) {
        prefs.edit().remove(eventKey).apply()
    } else {
        prefs.edit().putString(eventKey, habitName).apply()
    }
}

/**
 * Returns the Tail habit name mapped to [eventKey], or null if no mapping is set.
 */
fun getTailMapping(context: Context, eventKey: String): String? =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(eventKey, null)
        ?.takeIf { it.isNotEmpty() }

/**
 * Fires an increment to Tail for [eventKey] if a mapping exists.
 * This is the main function to call when an event occurs in your app.
 *
 * Example:
 *   fireTailEvent(context, "walk_completed")
 */
fun fireTailEvent(context: Context, eventKey: String) {
    val habitName = getTailMapping(context, eventKey) ?: return
    incrementTailHabit(context, habitName)
}
```

---

## Part 7 — Checklist: Everything Your App Needs

Use this as a final verification checklist before testing.

### In Your App's `AndroidManifest.xml`

- [ ] `<uses-permission android:name="com.example.tail.permission.TAIL_INTEGRATION" />` is present inside `<manifest>` (not inside `<application>`)

### In Your App's Code

- [ ] `TailIntegration.kt` (or equivalent) is present with the correct package name at the top
- [ ] `fetchTailHabits(context)` is called on a background thread / IO dispatcher
- [ ] `incrementTailHabit(context, habitName)` is called with the exact habit name string
- [ ] `sendBroadcast(intent, TAIL_PERMISSION)` passes the permission string as the second argument
- [ ] `setPackage("com.example.tail")` is set on the intent (required on Android 8+)

### Signing

- [ ] Both apps are built and signed with the **same keystore** (same `.jks` or `.keystore` file, same key alias)
- [ ] Both APKs are installed on the same device
- [ ] If testing in debug builds, both use the same debug keystore (Android Studio uses `~/.android/debug.keystore` by default — this is the same for all projects on the same machine, so debug builds automatically share it)

### Testing

- [ ] Open your app's settings screen — the Tail habit list loads without error
- [ ] Select a habit mapping for one of your events
- [ ] Trigger that event in your app
- [ ] Open Tail — the habit's count for today has increased by 1
- [ ] Check Logcat with tag `HabitIncrementReceiver` for success/error messages from Tail

---

## Part 8 — Troubleshooting

### The habit list is empty / settings screen shows an error

**Cause A:** Tail is not installed on the device.  
**Fix:** Install Tail first.

**Cause B:** The apps are not signed with the same keystore.  
**Fix:** Rebuild both apps from the same signing configuration. In Android Studio, check `Build > Generate Signed Bundle/APK` and confirm both use the same `.jks` file and key alias.

**Cause C:** Your app's manifest is missing the `<uses-permission>` line.  
**Fix:** Add `<uses-permission android:name="com.example.tail.permission.TAIL_INTEGRATION" />` to your manifest.

**Cause D:** You are calling `contentResolver.query()` on the main thread and it is being blocked or silently failing.  
**Fix:** Move the call inside `withContext(Dispatchers.IO) { ... }`.

---

### The broadcast is sent but Tail's count doesn't change

**Cause A:** `setPackage("com.example.tail")` is missing from the intent.  
**Fix:** Add `intent.setPackage("com.example.tail")`. On Android 8+, implicit broadcasts to exported receivers require an explicit package target.

**Cause B:** The habit name string doesn't exactly match a habit in Tail's database.  
**Fix:** Use `fetchTailHabits()` to get the exact names. Habit names are case-sensitive and must match character-for-character (e.g. `"Flossed"` not `"flossed"`).

**Cause C:** Tail has no habits file configured (the user hasn't picked `habitsdb_phone.txt` yet).  
**Fix:** Open Tail, tap the 📂 icon, and pick the habits file. The receiver logs `"No habits file URI configured"` to Logcat if this is the problem.

**Cause D:** The second argument to `sendBroadcast()` is missing or wrong.  
**Fix:** Always call `sendBroadcast(intent, "com.example.tail.permission.TAIL_INTEGRATION")` with the permission string as the second argument.

---

### Logcat tags to watch

| Tag | App | What it shows |
|-----|-----|---------------|
| `HabitIncrementReceiver` | Tail | Success/failure of each increment attempt |
| `HabitsContentProvider` | Tail | (no explicit logging, but SecurityExceptions appear here) |
| Your app's tag | Your app | Add your own logging around `sendBroadcast()` calls |

To filter Logcat in Android Studio: `tag:HabitIncrementReceiver`

---

## Part 9 — Quick Reference Card

```
PERMISSION:   com.example.tail.permission.TAIL_INTEGRATION
              (signature-level — same keystore required)

READ HABITS:  content://com.example.tail.provider/habits
              Columns: habit_id (Int), habit_name (String)
              Call: contentResolver.query(uri, null, null, null, null)
              Thread: IO dispatcher only

INCREMENT:    Action:  com.example.tail.ACTION_INCREMENT_HABIT
              Extra:   EXTRA_HABIT_ID = "Habit Name" (String, preferred)
                    or EXTRA_HABIT_ID = 42 (Int, 0-based index)
              Target:  setPackage("com.example.tail")
              Send:    sendBroadcast(intent, "com.example.tail.permission.TAIL_INTEGRATION")
              Thread:  any thread, fire-and-forget

MANIFEST:     <uses-permission android:name="com.example.tail.permission.TAIL_INTEGRATION" />
              (inside <manifest>, not <application>)
```

---

*This guide covers the complete IPC surface of Tail as of 2026-03-14. The two endpoints (ContentProvider + BroadcastReceiver) are the only supported integration points. Direct file access to `habitsdb_phone.txt` is not recommended as it bypasses Tail's atomic read-modify-write logic and risks data corruption.*
