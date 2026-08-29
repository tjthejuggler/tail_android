#!/usr/bin/env python3
"""One-shot refactor (2026-08-29): split HabitViewModel.kt and HabitGridScreen.kt
into smaller files to reduce Kotlin IR-lowering memory pressure.

Strategy:
  - HabitViewModel.kt: move cohesive member-function groups into new files in the
    same package as *extension functions* on HabitViewModel. Same-package
    extensions resolve on the implicit receiver, so existing call sites inside
    the class and the ui package keep compiling unchanged. All `private`
    modifiers in the file become `internal` so the extensions can reach state.
  - HabitGridScreen.kt: top-level composables are redistributed into sibling
    files by declaration boundaries; `private` becomes `internal` so
    cross-file references work.
Run from the repo root. Idempotency: NOT idempotent — restore from git first.
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VM = ROOT / "app/src/main/java/com/example/tail/ui/HabitViewModel.kt"
GRID = ROOT / "app/src/main/java/com/example/tail/ui/HabitGridScreen.kt"
UI = ROOT / "app/src/main/java/com/example/tail/ui"

# ---------------------------------------------------------------- groups ----
VM_GROUPS = {
    "HabitViewModelMigrations.kt": [
        "performApneaSecondaryMigration", "performResonanceSecondaryMigration",
        "performMinutesSlotMigration", "performMinutesToggleInit",
        "performMinutesWidgetBackfill", "performWagsMinutesPrimaryRepair",
        "performBrokenMinutesMigrationRepair", "performApneaSessionsPrimaryMigration",
        "performBreathingSessionsPrimaryMigration", "performChessTimestampTrim",
    ],
    "HabitViewModelData.kt": [
        "catchUpAndLoad", "runAutoRestoreIfNeeded", "performRollForwardIfNeeded",
        "loadFromFile", "rebuildHabitList", "setFileUri", "setScreensRelayFileUri",
        "sendHabitIncrementedBroadcast", "togglePcWidgetHabit", "navigateDay",
        "navigateToDate", "getDailyTotals", "incrementHabit",
        "incrementHabitWithRollForward", "updateTextEntryWithRollForward",
        "setTextEntryForDateWithRollForward", "setTextEntriesForDateWithRollForward",
        "loadSnapshots", "restoreSnapshot", "clearSnapshotStatus",
        "previewHabitRestore", "cancelHabitRestore", "clearHabitRestoreStatus",
        "applyHabitRestore", "refreshAfterExternalDbChange", "activeHabitOrder",
        "screenIndexForHabit", "getCachedDatabase",
    ],
    "HabitViewModelHabitConfig.kt": [
        "customRangePointsForInput", "setHabitCount", "getSecondaryTodayCount",
        "setHabitSecondaryCount", "setHabitCountWithRollForward", "toggleMaxOne",
        "toggleInvertedBinary", "previewMaxOneAffectedDays", "applyMaxOneToHistory",
        "previewMaxOneRestorableDays", "restoreMaxOneFromTimestamps",
        "toggleCustomInput", "setCustomInputAmounts", "recordRecentIncrementAmount",
        "setHabitDivider", "toggleConditional", "performConditionalBackfill",
        "toggleSubtyped", "setHabitSubtypes", "loadSubtypeBreakdown",
        "saveSubtypeIncrement", "recordRecentExercise", "saveWeightsEntry",
        "getWeightsDayValues", "setWeightsDayValues", "deleteWeightsDay",
        "toggleTimed", "toggleTimeless", "toggleRollForward", "toggleDisabledHabit",
        "toggleNoPointsHabit", "toggleSecondaryValueHabit",
        "toggleSecondaryValueFallbackHabit", "hasSecondaryValueFallback",
        "getValueDisplayLabel", "setValueDisplayLabel", "effectivePointsForDate",
        "scheduleInstancePoints", "getMinutesTodayCount", "setHabitMinutesCount",
        "toggleMinutesFallbackHabit", "getMinutesPrimaryFallback",
        "setMinutesPrimaryFallback", "isMinutesEnabled", "isMinutesForcedByWidget",
        "toggleMinutesEnabled", "isMinutesPrimaryHabit", "valueDisplayLabel",
        "customValueLabel", "isMaxOneHabit", "toggleTimelineExcluded",
        "toggleCustomPointRanges", "setCustomPointRanges",
        "recalculateHabitPointsForCustomRanges", "setHabitLongPressAction",
        "setHabitLongPressUrl", "setHabitLongPressUrlApp", "setHabitIcon",
        "setHabitNote", "isMealHabit", "isWeightsHabit", "hasSecondaryValue",
        "isGithubHabit",
    ],
    "HabitViewModelScreens.kt": [
        "toggleEditMode", "selectEditHabit", "startMoveMode", "beginHabitDrag",
        "commitHabitMove", "commitCrossScreenDrag", "applyMove", "switchScreen",
        "addScreen", "deleteScreen", "renameScreen", "toggleScreenHidden",
        "reorderScreen", "moveHabitToScreen", "persistScreens",
        "writeScreensRelayFile", "pushPcWidgetConfig", "addHabit", "addAppLink",
        "deleteAppLink", "addHabitAppAssociation", "removeHabitAppAssociation",
        "moveHabitAppAssociation", "clearHabitAppAssociations", "toggleWidgetTrigger",
        "setWidgetTriggerApp", "setWidgetTimerPrimaryValue",
        "migrateValue1ToMinutesPrimary", "deleteHabit", "removeConditionalReferences",
        "getDeleteDataDayCount", "deleteHabitData", "renameHabit",
        "getInvertPreview", "invertHabit",
    ],
    "HabitViewModelMedia.kt": [
        "toggleMediaHabit", "setMediaApp", "hasNotificationListenerAccess",
        "openNotificationListenerSettings", "parseMediaShowEntry",
        "loadMediaTodayShows", "removeMediaShowFromToday", "hasUsageAccess",
        "openUsageAccessSettings", "updateWidgetTriggerService",
    ],
    "HabitViewModelChess.kt": [
        "setChessReadinessEnabled", "setStatsOverlayEnabled",
        "setAppStatsRecordNotificationsEnabled", "setChessReadinessApp",
        "setChessReadinessVersion", "syncSurvivalPbFromChessCom",
        "setChessPhase2Version", "setChessEnforcementEnabled",
    ],
    "HabitViewModelAiIcons.kt": [
        "saveAiIconSettings", "fetchAiModels", "refreshAiIcons", "generateAiIcon",
        "deleteAiIcon", "getAiIconRepo", "clearAiIconError",
    ],
    "HabitViewModelTextInput.kt": [
        "toggleTextInput", "toggleTextInputOptions", "toggleSharableText",
        "setInuitIntegrationEnabled", "toggleInuitTextHabit", "setTextInputFileUri",
        "createTextInputFileInDir", "sanitizeFileDisplayName", "saveTextEntry",
        "saveTextEntries", "loadTextOptions",
    ],
    "HabitViewModelGraphs.kt": [
        "setGraphTimePeriod", "setGraphWeightUnit", "setGraphZoomRange",
        "clearGraphZoom", "toggleGraphMode", "toggleGraphHabitSelection",
        "toggleScheduleMode", "setGraphValueMode", "getGraphValueMode",
        "clearGraphSelection", "getAvailableMetrics", "getSelectedMetrics",
    ],
    "HabitViewModelGarmin.kt": [
        "stopGarminPolling", "syncGarminCurrentMonth", "mergeIntoGarminMonthlyData",
        "fetchGarminBacklog", "resetGarminHabitData", "testGarminConnection",
        "syncGarminBacklog", "garminTypeKeywords", "autoLinkMissingGarminHabits",
        "applyGarminData", "importGarminHistoricData", "deriveBridgeUrl",
        "getBridgeConnection", "saveBridgeSettings", "setWallpaperStatus",
        "saveWallpaperSettings",
    ],
    "HabitViewModelMovies.kt": [
        "toggleBridgeMovieHabit", "fetchMovieSuggestion", "clearMovieSuggestion",
        "streamMovieSuggestion", "prepareMoviePrompt", "annotatedMovieTitle",
        "confirmMoviePrompt", "dismissMoviePrompt", "markMoviePromptHandled",
        "markMovieMarkerHandled", "testBridgeConnection", "testChessAnalysisPipeline",
        "saveOmdbApiKey", "isMovieBridgeHabit", "hasImdbRatings",
        "getImdbRatingForText", "fetchAndCacheImdbRating", "updateImdbSecondaryValues",
        "triggerImdbFetchForEntry", "fetchImdbBacklog", "fetchBridgeDurations",
        "bridgeMinutesFor", "fetchMovieMinutesBacklog",
        "maybeRunMovieMinutesBackfill", "getOmdbRemainingCalls",
        "getImdbRatingsForDate", "importMeditationData",
    ],
    "HabitViewModelVoice.kt": [
        "saveVoiceTriggerEnabled", "toggleVoiceTrigger", "setVoiceTriggerWords",
        "setVoiceTriggerIncrement", "toggleVoiceSubtype", "saveVoiceNoteEnabled",
        "saveVoiceNoteFileUri", "saveAiAssistantSettings",
    ],
    "HabitViewModelLocations.kt": [
        "refreshTodayLocation", "setLocationForDate", "removeLocationForDate",
        "fetchFreshLocationForDate", "fetchLocationCandidates",
        "savePreferredAutoCandidateIndex", "getAllStoredLocations",
        "getAllStoredLabels", "getCoordsForDate", "setCoordsForDate",
        "getLocationLabelForDate", "getAssumedLocationForDate", "getDatesWithCoords",
        "getAllStoredCoordsParsed", "getAllStoredLabelsParsed", "buildCountryTimeline",
        "getIgnoredCountryNames", "addIgnoredCountryName", "removeIgnoredCountryName",
        "getSecondaryLocationsForDate", "getAllSecondaryLocations",
        "logSecondaryLocationOnForeground", "addManualSecondaryLocation",
        "removeSecondaryLocation", "updateSecondaryLocationTime",
        "getDayHabitBreakdown", "getEarliestLocationDate",
    ],
    "HabitViewModelDayStats.kt": [
        "getDayStatsLight", "getMonthlyAveragesBulk", "getLoadingMetrics",
        "getDayStats", "trackedHabitNames", "recalculateFitnessAgeDistance",
    ],
    "HabitViewModelMeals.kt": [
        "saveMealSettings", "toggleMealHabit", "toggleWeightsHabit",
        "toggleCameraHabit", "refreshVisionMemory", "updateVisionMemoryEntry",
        "deleteVisionMemoryEntry", "loadMealLogs", "recordMealTap",
        "addManualMealLog", "updateMealLog", "deleteMealLog", "processVoiceMeal",
        "addMealPhotoFromUri", "clearMealVoiceStatus", "recordMealIncrement",
        "rollbackMealIncrement", "deleteMealStampNear", "refreshMealFlows",
        "triggerVisionProcessing", "testVisionEndpoint",
    ],
}

GRID_SPLIT = [  # (filename, first top-level decl start line that goes into it)
    ("HabitGridScreenSections.kt", 3590),      # AppLinkEditSection ..
    ("HabitGridEditSections.kt", 6975),        # HabitInputModesSection ..
    ("HabitGridDialogs.kt", 8164),             # DatedEntryInfoDialog ..
    ("HabitGridChessSections.kt", 9376),       # ChessComLinkToggle ..
]

MEMBER_DECL = re.compile(
    r"^    (?:@[A-Za-z]|(?:internal|private|public|protected)\s+)?"
    r"(?:(?:suspend|inline|operator|infix|tailrec)\s+)*fun\s+"
    r"(?:<[^>]+>\s+)?([A-Za-z_]\w*)\s*[<(]"
)
ANY_MEMBER = re.compile(
    r"^    (?:@[A-Za-z]|(?:internal|private|public|protected)\s+)?"
    r"(?:(?:suspend|inline)\s+)?(?:fun|val|var|class|object|companion|init|constructor|enum)\b"
)
TOP_DECL = re.compile(
    r"^(?:fun|private fun|internal fun|val|private val|internal val|var|class|"
    r"private class|data class|object|enum class|private object|@Composable)\b"
)
SIG = re.compile(
    r"^((?:internal|public)\s+)?((?:suspend|inline|operator|infix)\s+)*fun\s+(?:<[^>]+>\s+)?([A-Za-z_]\w*)"
)


MODKW = r"(fun|val|var|set\b|class|object|companion|constructor|interface|enum|lateinit|const|suspend|inline|operator)"


def mod_visibility(line):
    # only rewrite `private <modifier-keyword>` (never prose inside strings)
    return re.sub(rf"\bprivate\s+(?={MODKW})", "internal ", line)


def imports_of(lines):
    return [l for l in lines if l.startswith("import ")]


def walk_back(lines, i):
    """Include preceding KDoc/comment/annotation lines (4-space or top-level)."""
    while i > 0:
        prev = lines[i - 1]
        if re.match(r"^ {4}\s*(///|/\*|\*|//|@)", prev) or re.match(r"^(///|/\*|\*|//|@)", prev):
            if prev.strip().startswith("*/") or "*" in prev or prev.strip().startswith("//") \
               or prev.strip().startswith("///") or prev.strip().startswith("@"):
                i -= 1
                continue
        break
    return i


def split_vm():
    src = VM.read_text()
    if "private" in re.sub(r"//.*|\"[^\"]*\"", "", src).count("private") * "private":
        pass  # visibility replace below is unconditional
    lines = src.split("\n")
    # 1. private -> internal everywhere in this file (member visibility).
    lines = [mod_visibility(l) for l in lines]

    # 2. locate member functions of the class
    class_start = next(i for i, l in enumerate(lines) if l.startswith("class HabitViewModel("))
    decls = []  # (start, name)
    for i in range(class_start, len(lines)):
        m = MEMBER_DECL.match(lines[i])
        if m:
            decls.append((i, m.group(1)))
    # boundaries: next ANY_MEMBER line after each decl start
    extents = {}
    for idx, (start, name) in enumerate(decls):
        end = len(lines)
        for j in range(start + 1, len(lines)):
            if ANY_MEMBER.match(lines[j]) or lines[j].startswith("    }"):
                if lines[j].startswith("    }"):
                    # closing brace of previous member; next member starts after
                    continue
                end = j
                break
        else:
            end = len(lines)
        # find next member decl after start (any member incl. properties)
        nxt = len(lines)
        for j in range(start + 1, len(lines)):
            if ANY_MEMBER.match(lines[j]):
                nxt = j
                break
        real_end = nxt
        # strip trailing blank lines
        while real_end > start and not lines[real_end - 1].strip():
            real_end -= 1
        extents.setdefault(name, []).append((walk_back(lines, start), real_end))

    moved = {}
    remove = set()
    missing = []
    for fname, names in VM_GROUPS.items():
        chunks = []
        for n in names:
            if n not in extents:
                missing.append(n)
                continue
            for (s, e) in extents[n]:
                block = lines[s:e]
                remove.update(range(s, e))
                # dedent 4 and rewrite signature
                block = [l[4:] if l.startswith("    ") else l for l in block]
                for bi, bl in enumerate(block):
                    m = SIG.match(bl)
                    if m:
                        mods = m.group(1) or ""
                        kw = m.group(2) or ""
                        block[bi] = f"{mods}{kw}fun HabitViewModel.{m.group(3)}" + bl[m.end():]
                        break
                chunks.append("\n".join(block))
        moved[fname] = chunks

    # 3. rebuild original file without moved lines
    out = [l for i, l in enumerate(lines) if i not in remove]
    # collapse triple blank lines
    text = "\n".join(out)
    text = re.sub(r"\n{4,}", "\n\n\n", text)
    VM.write_text(text)

    header_imports = "\n".join(imports_of(lines))
    for fname, chunks in moved.items():
        if not chunks:
            continue
        body = "\n\n\n".join(chunks)
        content = (
            "package com.example.tail.ui\n\n"
            "// Split out of HabitViewModel.kt (2026-08-29) to keep individual\n"
            "// Kotlin source files small enough for IR lowering on this machine.\n\n"
            f"{header_imports}\n\n{body}\n"
        )
        (UI / fname).write_text(content)

    remaining = [l for i, l in enumerate(lines) if i not in remove]
    print(f"HabitViewModel.kt: {len(lines)} -> {len(remaining)} lines")
    for fname, chunks in moved.items():
        n = sum(len(c.split("\n")) for c in chunks)
        print(f"  {fname}: {n} lines, {len(chunks)} functions")
    if missing:
        print("MISSING (not found):", ", ".join(missing))


def split_grid():
    lines = GRID.read_text().split("\n")
    lines = [mod_visibility(l) for l in lines]
    decl_starts = []
    for i, l in enumerate(lines):
        if TOP_DECL.match(l):
            decl_starts.append(walk_back(lines, i))
    decl_starts = sorted(set(decl_starts))
    header_imports = "\n".join(imports_of(lines))
    prelude_end = decl_starts[0]  # package + imports + comments before first decl

    buckets = {}
    for idx, s in enumerate(decl_starts):
        e = decl_starts[idx + 1] if idx + 1 < len(decl_starts) else len(lines)
        for fname, threshold in reversed(GRID_SPLIT):
            if s >= threshold:
                buckets.setdefault(fname, []).append((s, e))
                break
        else:
            buckets.setdefault("__main__", []).append((s, e))

    for fname in list(buckets):
        chunks = []
        for (s, e) in buckets[fname]:
            while e > s and not lines[e - 1].strip():
                e -= 1
            chunks.append("\n".join(lines[s:e]))
        body = "\n\n\n".join(chunks)
        if fname == "__main__":
            GRID.write_text("\n".join(lines[:prelude_end]).rstrip() + "\n\n\n" + body + "\n")
            print(f"HabitGridScreen.kt: now {prelude_end + len(body.split(chr(10)))} lines")
        else:
            content = (
                "package com.example.tail.ui\n\n"
                "// Split out of HabitGridScreen.kt (2026-08-29) to keep individual\n"
                "// Kotlin source files small enough for IR lowering on this machine.\n\n"
                f"{header_imports}\n\n{body}\n"
            )
            (UI / fname).write_text(content)
            print(f"  {fname}: {len(body.splitlines())} lines")


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "both"
    if what in ("vm", "both"):
        split_vm()
    if what in ("grid", "both"):
        split_grid()
