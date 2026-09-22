#!/usr/bin/env bash
# Phase 1a — repackage flat ui/ into feature sub-packages (2026-09-22).
# Pure moves: git mv + package-line rewrite + FQN/import rewrite everywhere.
set -euo pipefail
cd /home/twain/AndroidStudioProjects/tail

UI=app/src/main/java/com/example/tail/ui

move() { # move <File> <subpkg>
  local f="$1" sub="$2"
  mkdir -p "$UI/$sub"
  git mv "$UI/$f.kt" "$UI/$sub/$f.kt"
  perl -pi -e "s/^package com\\.example\\.tail\\.ui\$/package com.example.tail.ui.$sub/" "$UI/$sub/$f.kt"
}

# ── grid ─────────────────────────────────────────────────────────────────────
for f in HabitGridScreen HabitGridScreenSections HabitGridChessSections HabitGridDialogs \
         HabitGridEditBarHost HabitGridEditSections HabitGridTextDialogHost HabitButton \
         HabitAskFlash MovieConfirmFlash MovieMinutesEditor IncrementDialog \
         SubtypeIncrementDialog WeightsInputDialog CalendarPickerDialog \
         HabitIncrementConfirmActivity HabitIncrementToast; do move "$f" grid; done
# ── chess ────────────────────────────────────────────────────────────────────
for f in ChessReadinessCorrelationSections ChessReadinessEffectiveness ChessReadinessHourlyCharts \
         ChessReadinessInteractiveChart ChessReadinessPuzzleCharts ChessReadinessStatsScreen \
         ChessReadinessV2Sections ChessReadinessV3Sections ChessReflexSections; do move "$f" chess; done
# ── stats ────────────────────────────────────────────────────────────────────
for f in AppStatsScreen MapStatsScreen GraphsScreen StreakGraphPopup SleepTimelineChart; do move "$f" stats; done
# ── settings ─────────────────────────────────────────────────────────────────
for f in SettingsScreen AiAssistantSettingsSection AutoBackupSection BackupSettingsSection \
         GoogleDriveSection QuickCaptureSettingsSection MapSettingsDialog \
         SnapshotRestoreSection TextInputOptionsEditorDialog; do move "$f" settings; done
# ── meals ────────────────────────────────────────────────────────────────────
for f in MealDetailScreen MealEditorContent MealSettingsSection VisionMemorySection; do move "$f" meals; done
# ── advice ───────────────────────────────────────────────────────────────────
for f in AdviceBanner AdviceDialog AdviceNoteDialog AdviceViewModel; do move "$f" advice; done
# ── loading ──────────────────────────────────────────────────────────────────
for f in HabitLoadingDaily HabitLoadingLayers HabitLoadingMonthly HabitLoadingSpinner \
         HabitLoadingThreaded HabitLoadingWeekly; do move "$f" loading; done
# ── map (fold into existing ui/map) ─────────────────────────────────────────
for f in MapScreen LocationEditDialog; do move "$f" map; done
# ── viewmodel ────────────────────────────────────────────────────────────────
for f in HabitViewModel HabitViewModelAiIcons HabitViewModelChess HabitViewModelData \
         HabitViewModelDayStats HabitViewModelGarmin HabitViewModelGraphs \
         HabitViewModelHabitConfig HabitViewModelLocations HabitViewModelMeals \
         HabitViewModelMedia HabitViewModelMigrations HabitViewModelMovies \
         HabitViewModelScreens HabitViewModelSleep HabitViewModelTextInput \
         HabitViewModelVoice; do move "$f" viewmodel; done
# ── common ───────────────────────────────────────────────────────────────────
for f in SteelPanel WheelPicker MarkdownText HabitColors PointTierColors RepeatIconButton \
         TimestampEditorDialog QuickTimestampEditorDialog TextInputDialog SectionExpansionStore \
         SpeechRecognition HabitsDataChangedBus VoiceNoteBus VoiceTranscriptBus HabitHaptics \
         NotificationsDialog SearchDialog SleepDialogs QuickCaptureHistoryScreen \
         AiAssistantDialog LizardPerch ScheduleScreen; do move "$f" common; done

echo "moves done; rewriting FQNs and imports..."
# Longest names first so prefix names can't shadow longer ones (belt & braces; \b guards anyway).
mapfile -t NAMES < <(find "$UI" -maxdepth 2 -name '*.kt' -printf '%f\n' | sed 's/\.kt$//' | awk '{print length, $0}' | sort -rn | awk '{print $2}')
for name in "${NAMES[@]}"; do
  # find its new subpackage (relative dir under ui/)
  sub=$(find "$UI" -maxdepth 2 -name "$name.kt" -printf '%h\n' | sed "s|$UI/||" | head -1)
  [ "$sub" = "map" ] || [ "$sub" = "debug" ] || [ "$sub" = "theme" ] || true
  # rewrite qualified refs in ALL Kotlin sources (app + tests + core-data)
  grep -rl --include='*.kt' "com\.example\.tail\.ui\.$name\b" app/src core-data/src 2>/dev/null | while read -r tgt; do
    perl -pi -e "s/\\bcom\\.example\\.tail\\.ui\\.$name\\b/com.example.tail.ui.$sub.$name/g" "$tgt"
  done
done
echo "repackage complete"
