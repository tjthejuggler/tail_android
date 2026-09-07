package com.example.tail.data

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.os.Process
import android.util.Log

/**
 * Repository for app shortcuts (the "long-press menu" shortcuts apps publish
 * via ShortcutManager / static XML shortcuts).
 *
 * Habit app associations historically store plain package names. To let a
 * longhold open a *specific* shortcut of an app, association entries can now
 * also be encoded shortcut references:
 *
 *   "com.example.app|<escaped shortcut id>"
 *
 * `|` is not a legal character in Android package names, so plain package
 * entries can never be mistaken for shortcut entries. The shortcut id is
 * escaped (`\`, `|`, `,`) so the encoding survives the surrounding
 * `\,`-escaping used by SettingsRepository.encodeSubtypesMap.
 *
 * Two kinds of entries are supported:
 *  - LauncherApps shortcuts (manifest/dynamic/pinned). Android only serves
 *    these to the current/default launcher (hasShortcutHostPermission), so
 *    they may be unavailable — surfaced as a hint in the picker.
 *  - Exported activities of the app, encoded as ids prefixed with
 *    [ACTIVITY_ID_PREFIX]. These are launchable by ANY app via an explicit
 *    intent — the same mechanism Tasker's "Launch App / All Activities"
 *    uses (e.g. Tail's own exported MediaCaptureActivity quick capture).
 */
private const val TAG = "AppShortcutRepository"

/** Separator between package name and shortcut id inside an association entry. */
private const val SHORTCUT_ENTRY_SEP = '|'

/** Prefix marking an entry id that references an exported activity, not a published shortcut. */
const val ACTIVITY_ID_PREFIX = "activity:"

/** A shortcut published by an app, resolved through LauncherApps. */
data class AppShortcutInfo(
    val packageName: String,
    /** LauncherApps shortcut id, or [ACTIVITY_ID_PREFIX] + class name for activity entries. */
    val shortcutId: String,
    val label: String,
    val isManifest: Boolean = false,
    val isDynamic: Boolean = false,
    val isPinned: Boolean = false,
    /** True when this entry is an exported activity rather than a published shortcut. */
    val isActivity: Boolean = false
)

/** Result of expanding an app in the association picker. */
data class AppShortcutQueryResult(
    /**
     * True when LauncherApps served published shortcuts. False means Tail is
     * not the current/default launcher — the picker shows a hint, and the
     * exported-activity list below is still fully usable.
     */
    val shortcutsAccessible: Boolean,
    /** Published (manifest/dynamic/pinned) shortcuts. Empty when inaccessible. */
    val shortcuts: List<AppShortcutInfo>,
    /** Exported activities launchable by any app (Tasker-style). */
    val activities: List<AppShortcutInfo>
) {
    val all: List<AppShortcutInfo> get() = shortcuts + activities
    val isEmpty: Boolean get() = shortcuts.isEmpty() && activities.isEmpty()
}

// ── Association-entry codec (pure, unit-testable) ─────────────────────────────

/**
 * Encodes a specific app shortcut as a habit-app-association entry.
 * Package names never contain `|`, `\` or `,` so only the id needs escaping.
 */
fun encodeShortcutEntry(packageName: String, shortcutId: String): String =
    "$packageName$SHORTCUT_ENTRY_SEP${escapeShortcutId(shortcutId)}"

/**
 * Parses an association entry into (packageName, shortcutId).
 * Returns null when the entry is a plain package name (not a shortcut).
 */
fun parseShortcutEntry(entry: String): Pair<String, String>? {
    var i = 0
    while (i < entry.length) {
        val c = entry[i]
        if (c == '\\') {
            i++ // skip the escaped character
        } else if (c == SHORTCUT_ENTRY_SEP) {
            val pkg = entry.substring(0, i)
            if (pkg.isEmpty() || i == entry.length - 1) return null
            return pkg to unescapeShortcutId(entry.substring(i + 1))
        }
        i++
    }
    return null
}

/** True when the association entry references a specific shortcut, not a whole app. */
fun isShortcutEntry(entry: String): Boolean = parseShortcutEntry(entry) != null

/** True when the entry references an exported activity (Tasker-style launch). */
fun isActivityEntry(entry: String): Boolean =
    parseShortcutEntry(entry)?.second?.startsWith(ACTIVITY_ID_PREFIX) == true

/** Package name part of an association entry (works for plain apps and shortcuts). */
fun packageNameOfEntry(entry: String): String =
    parseShortcutEntry(entry)?.first ?: entry

private fun escapeShortcutId(id: String): String = buildString {
    for (c in id) {
        when (c) {
            '\\', '|', ',' -> {
                append('\\'); append(c)
            }
            else -> append(c)
        }
    }
}

private fun unescapeShortcutId(escaped: String): String = buildString {
    var i = 0
    while (i < escaped.length) {
        val c = escaped[i]
        if (c == '\\' && i + 1 < escaped.length) {
            append(escaped[i + 1])
            i += 2
        } else {
            append(c)
            i++
        }
    }
}

// ── Discovery ─────────────────────────────────────────────────────────────────

/**
 * Expands an app into everything a longhold can be bound to:
 * its published shortcuts (when accessible) plus its exported activities.
 */
fun queryAppShortcuts(context: Context, packageName: String): AppShortcutQueryResult {
    val published = queryPublishedShortcuts(context, packageName)
    return AppShortcutQueryResult(
        shortcutsAccessible = published != null,
        shortcuts = published ?: emptyList(),
        activities = queryExportedActivities(context, packageName)
    )
}

/**
 * Returns the shortcuts an app has published (manifest + dynamic + pinned,
 * deduplicated by id, manifest shortcuts first), or null when this app is
 * not allowed to read them — LauncherApps only serves shortcuts to the
 * current/default launcher (hasShortcutHostPermission).
 */
private fun queryPublishedShortcuts(context: Context, packageName: String): List<AppShortcutInfo>? {
    return try {
        val launcherApps = context.getSystemService(LauncherApps::class.java) ?: return null
        val query = LauncherApps.ShortcutQuery()
            .setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
            .setPackage(packageName)
        val shortcuts: List<ShortcutInfo> =
            launcherApps.getShortcuts(query, Process.myUserHandle()) ?: emptyList()
        shortcuts
            .groupBy { it.id }
            .map { (_, infos) ->
                val info = infos.first()
                AppShortcutInfo(
                    packageName = packageName,
                    shortcutId = info.id ?: "",
                    label = info.shortLabel?.toString()
                        ?: info.longLabel?.toString()
                        ?: info.id ?: "",
                    isManifest = info.isDeclaredInManifest,
                    isDynamic = info.isDynamic,
                    isPinned = infos.any { it.isPinned }
                )
            }
            .filter { it.shortcutId.isNotEmpty() }
            .sortedWith(compareByDescending<AppShortcutInfo> { it.isManifest }.thenBy { it.label.lowercase() })
    } catch (e: SecurityException) {
        Log.w(TAG, "No shortcut access (not default launcher): ${e.message}")
        null
    } catch (e: Exception) {
        Log.w(TAG, "Failed to query shortcuts for $packageName: ${e.message}")
        null
    }
}

/**
 * Enumerates the app's exported activities — launchable by any app via an
 * explicit intent. This is the Tasker-style path and needs no launcher
 * status (Tail's own exported MediaCaptureActivity is launched this way by
 * Tasker on this device). The main launcher entry is excluded; it is already
 * offered as the plain app row.
 */
fun queryExportedActivities(context: Context, packageName: String): List<AppShortcutInfo> {
    return try {
        val pm = context.packageManager
        val mainActivity = pm.getLaunchIntentForPackage(packageName)?.component?.className
        val activities: List<ActivityInfo> =
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                .activities?.toList() ?: emptyList()
        activities
            .asSequence()
            .filter { it.exported && it.enabled }
            .filter { it.name != mainActivity }
            .map { ai ->
                val label = ai.loadLabel(pm).toString().ifBlank {
                    ai.name.substringAfterLast('.')
                }
                AppShortcutInfo(
                    packageName = packageName,
                    shortcutId = ACTIVITY_ID_PREFIX + ai.name,
                    label = label,
                    isActivity = true
                )
            }
            .distinctBy { it.shortcutId }
            .sortedBy { it.label.lowercase() }
            .toList()
    } catch (e: Exception) {
        Log.w(TAG, "Failed to enumerate activities of $packageName: ${e.message}")
        emptyList()
    }
}

/**
 * Resolves a single entry (published shortcut or exported activity) back to
 * its display info, or null when it is gone (unpublished, app updated) or
 * inaccessible.
 */
fun findShortcutInfo(context: Context, packageName: String, shortcutId: String): AppShortcutInfo? {
    if (shortcutId.startsWith(ACTIVITY_ID_PREFIX)) {
        val className = shortcutId.removePrefix(ACTIVITY_ID_PREFIX)
        return try {
            val ai = context.packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
                .activities
                ?.firstOrNull { it.name == className } ?: return null
            AppShortcutInfo(
                packageName = packageName,
                shortcutId = shortcutId,
                label = ai.loadLabel(context.packageManager).toString().ifBlank {
                    className.substringAfterLast('.')
                },
                isActivity = true
            )
        } catch (e: Exception) {
            null
        }
    }
    return queryPublishedShortcuts(context, packageName)
        ?.firstOrNull { it.shortcutId == shortcutId }
}

// ── Launching ─────────────────────────────────────────────────────────────────

/**
 * Launches the shortcut/activity referenced by an association entry.
 *
 * Strategy:
 *  1. Exported-activity entries — plain explicit intent (works for any app,
 *     no launcher status needed).
 *  2. LauncherApps.startShortcut — the canonical path for published
 *     shortcuts (works when Tail is the current/default launcher).
 *  3. Fall back to the shortcut's own launch intents (resolved via a fresh
 *     query) — works even when startShortcut is refused.
 *  4. Fall back to launching the app itself so the longhold never dead-ends.
 *
 * Returns true when something was actually started.
 */
fun launchShortcutEntry(context: Context, entry: String): Boolean {
    val (pkg, id) = parseShortcutEntry(entry) ?: return false
    val launcherApps = context.getSystemService(LauncherApps::class.java)

    // 1. Exported activity — explicit intent, no privileges required
    if (id.startsWith(ACTIVITY_ID_PREFIX)) {
        val className = id.removePrefix(ACTIVITY_ID_PREFIX)
        try {
            val intent = Intent()
                .setClassName(pkg, className)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Activity launch failed for $pkg/$className: ${e.message}")
        }
    } else {
        // 2. Canonical path for published shortcuts
        try {
            launcherApps?.startShortcut(pkg, id, null, null, Process.myUserHandle())
            return true
        } catch (e: SecurityException) {
            Log.w(TAG, "startShortcut refused, falling back to intents: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "startShortcut failed: ${e.message}")
        }

        // 3. Launch the shortcut's intents directly
        try {
            val query = LauncherApps.ShortcutQuery()
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
                .setPackage(pkg)
            val info = launcherApps
                ?.getShortcuts(query, Process.myUserHandle())
                ?.firstOrNull { it.id == id }
            val intents = info?.intents
            if (!intents.isNullOrEmpty()) {
                val intent = Intent(intents.last())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct shortcut intent launch failed: ${e.message}")
        }
    }

    // 4. Last resort — open the app's main activity
    return try {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            context.startActivity(launchIntent)
            true
        } else false
    } catch (e: Exception) {
        Log.w(TAG, "Fallback app launch failed: ${e.message}")
        false
    }
}
