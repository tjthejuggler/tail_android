package com.example.tail.ui.grid

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.example.tail.data.GitHubMetric
import com.example.tail.ui.viewmodel.invertHabit
import com.example.tail.ui.viewmodel.renameHabit
import com.example.tail.ui.viewmodel.openNotificationListenerSettings
import com.example.tail.ui.settings.TextInputOptionsEditorDialog
import com.example.tail.ui.viewmodel.HabitViewModel
import com.example.tail.ui.viewmodel.deleteScreen
import com.example.tail.ui.viewmodel.deleteWeightsDay
import com.example.tail.ui.viewmodel.getInvertPreview
import com.example.tail.ui.viewmodel.getMinutesTodayCount
import com.example.tail.ui.viewmodel.getWeightsDayValues
import com.example.tail.ui.viewmodel.hasNotificationListenerAccess
import com.example.tail.ui.viewmodel.hideTextOptionGrouping
import com.example.tail.ui.viewmodel.isMinutesEnabled
import com.example.tail.ui.viewmodel.isMinutesForcedByWidget
import com.example.tail.ui.viewmodel.loadMediaTodayShows
import com.example.tail.ui.viewmodel.loadTextOptionInventory
import com.example.tail.ui.viewmodel.moveHabitAppAssociation
import com.example.tail.ui.viewmodel.openUsageAccessSettings
import com.example.tail.ui.viewmodel.reapplyGarminHistoryForHabit
import com.example.tail.ui.viewmodel.removeHabitAppAssociation
import com.example.tail.ui.viewmodel.setEnvironmentHabitMetric
import com.example.tail.ui.viewmodel.removeMediaShowFromToday
import com.example.tail.ui.viewmodel.renameTextOption
import com.example.tail.ui.viewmodel.screenIndexForHabit
import com.example.tail.ui.viewmodel.setBubbleFullScreenMenu
import com.example.tail.ui.viewmodel.setBubbleMultiTimer
import com.example.tail.ui.viewmodel.setCustomInputAmounts
import com.example.tail.ui.viewmodel.setCustomPointRanges
import com.example.tail.ui.viewmodel.setHabitCount
import com.example.tail.ui.viewmodel.setHabitCountWithRollForward
import com.example.tail.ui.viewmodel.setHabitDivider
import com.example.tail.ui.viewmodel.setHabitLongPressAction
import com.example.tail.ui.viewmodel.setHabitLongPressUrl
import com.example.tail.ui.viewmodel.setHabitLongPressUrlApp
import com.example.tail.ui.viewmodel.setHabitMinutesCount
import com.example.tail.ui.viewmodel.setHabitNote
import com.example.tail.ui.viewmodel.setHabitSubtypes
import com.example.tail.ui.viewmodel.setMinutesPrimaryFallback
import com.example.tail.ui.viewmodel.setSleepVariant
import com.example.tail.ui.viewmodel.setTextOptionDescription
import com.example.tail.ui.viewmodel.setValueDisplayLabel
import com.example.tail.ui.viewmodel.setVoiceTriggerIncrement
import com.example.tail.ui.viewmodel.setVoiceTriggerWords
import com.example.tail.ui.viewmodel.setWeightsDayValues
import com.example.tail.ui.viewmodel.setWidgetTimerPrimaryValue
import com.example.tail.ui.viewmodel.toggleBridgeMovieHabit
import com.example.tail.ui.viewmodel.toggleCameraHabit
import com.example.tail.ui.viewmodel.toggleConditional
import com.example.tail.ui.viewmodel.toggleCustomInput
import com.example.tail.ui.viewmodel.toggleCustomPointRanges
import com.example.tail.ui.viewmodel.toggleDisabledHabit
import com.example.tail.ui.viewmodel.toggleInvertedBinary
import com.example.tail.ui.viewmodel.toggleLockWidgetHabit
import com.example.tail.ui.viewmodel.toggleMealHabit
import com.example.tail.ui.viewmodel.toggleMediaHabit
import com.example.tail.ui.viewmodel.toggleMinutesEnabled
import com.example.tail.ui.viewmodel.toggleMinutesFallbackHabit
import com.example.tail.ui.viewmodel.toggleNoPointsHabit
import com.example.tail.ui.viewmodel.togglePcWidgetHabit
import com.example.tail.ui.viewmodel.toggleRollForward
import com.example.tail.ui.viewmodel.toggleScreenHidden
import com.example.tail.ui.viewmodel.toggleSecondaryValueFallbackHabit
import com.example.tail.ui.viewmodel.toggleSecondaryValueHabit
import com.example.tail.ui.viewmodel.toggleSharableText
import com.example.tail.ui.viewmodel.toggleSubtyped
import com.example.tail.ui.viewmodel.toggleTextInput
import com.example.tail.ui.viewmodel.toggleTextInputOptions
import com.example.tail.ui.viewmodel.toggleTimeless
import com.example.tail.ui.viewmodel.toggleTimelineExcluded
import com.example.tail.ui.viewmodel.toggleVoiceSubtype
import com.example.tail.ui.viewmodel.toggleVoiceTrigger
import com.example.tail.ui.viewmodel.toggleWeightsHabit
import com.example.tail.ui.viewmodel.toggleWidgetPersistentTimer
import com.example.tail.ui.viewmodel.toggleWidgetTrigger
import com.example.tail.ui.viewmodel.unhideTextOptionGrouping
import com.example.tail.ui.viewmodel.hasUsageAccess
import kotlinx.coroutines.launch

/**
 * Host wrapper for the [EditModeControlBar] call — the ~150-argument invocation
 * inside HabitGridScreen's Scaffold content lambda. Extracted verbatim so that
 * lambda stays under the JVM 64KB method-size limit (the sleep-suite additions
 * pushed it over; this codebase has hit the same limit before and extracted
 * sub-composables each time).
 *
 * Only the state that is OWNED by HabitGridScreen (dialog-open flags, SAF
 * launchers, timestamp-editor state) is passed through as callbacks; everything
 * derivable from [viewModel]/[settings] is read here directly. Behaviour is
 * identical to the original inline call.
 */
@Composable
internal fun EditBarHost(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings,
    selectedEditIndex: Int,
    selectedHabitName: String?,
    selectedHabitRawTodayCount: Int,
    selectedHabitTodayCount: Int,
    isPlaceholderSelected: Boolean,
    habitScreens: List<com.example.tail.data.HabitScreen>,
    activeScreenIndex: Int,
    selectedDate: java.time.LocalDate,
    selectedHabitTimestampCount: Int,
    editModeTextEntries: List<Pair<String, String>>,
    weightsDayExerciseNames: Map<String, String>,
    // ── state setters / launchers owned by HabitGridScreen ──
    onAddHabit: () -> Unit,
    onAddAppLink: () -> Unit,
    onShowAddScreen: () -> Unit,
    onToggleMaxOne: (String) -> Unit,
    onPickTextInputFile: (String) -> Unit,
    onCreateTextInputFile: (String) -> Unit,
    onPickDatedEntryFile: (String) -> Unit,
    onDeleteHabit: (String) -> Unit,
    onChangeIcon: (String) -> Unit,
    onSetConditionalLinks: (String) -> Unit,
    onBackfillConditional: (String) -> Unit,
    onOpenMealDetails: (String) -> Unit,
    onPickLongPressUrlApp: (String) -> Unit,
    onPickAppAssociation: (String) -> Unit,
    onPickWidgetTriggerApp: (String) -> Unit,
    onPickMediaApp: (String) -> Unit,
    onRestoreFromBackup: () -> Unit,
    onShowRollForward: (RollForwardDialogState) -> Unit,
    setEditModeTextEntries: (List<Pair<String, String>>) -> Unit,
    setTimestampHabit: (String?) -> Unit,
    setTimestampList: (List<String>) -> Unit,
    setTimestampMinutes: (Map<String, Int>) -> Unit
) {
    val garminMonthlyData by viewModel.garminMonthlyData.collectAsState()
    val githubSyncStatus by viewModel.githubSyncStatus.collectAsState()
    val timestampScope = rememberCoroutineScope()

    // ── Text-input options editor popup state ─────────────────────────────
    // Non-null = the popup is open for this habit; the inventory holds the
    // decomposed singles + groupings with usage counts, reloaded after every
    // rename/hide so the lists stay accurate. Descriptions and hidden sets
    // come live from settings (never stale).
    var optionsEditorHabit by remember { mutableStateOf<String?>(null) }
    var optionsEditorSingles by remember {
        mutableStateOf<List<Pair<String, Int>>>(emptyList())
    }
    var optionsEditorGroupings by remember {
        mutableStateOf<List<Pair<String, Int>>>(emptyList())
    }

    fun reloadOptionsEditor(habitName: String) {
        viewModel.loadTextOptionInventory(habitName) { inventory ->
            optionsEditorSingles = inventory.singles
            optionsEditorGroupings = inventory.groupings
        }
    }

    EditModeControlBar(
        selectedIndex = selectedEditIndex,
        selectedHabitName = selectedHabitName,
        selectedHabitRawTodayCount = selectedHabitRawTodayCount,
        selectedHabitTodayCount = selectedHabitTodayCount,
        isPlaceholderSelected = isPlaceholderSelected,
        habitScreens = habitScreens,
        activeScreenIndex = activeScreenIndex,
        selectedHabitScreenIndex = if (selectedHabitName != null)
            viewModel.screenIndexForHabit(selectedHabitName) else -1,
        maxOneHabits = settings.maxOneHabits,
        invertedBinaryHabits = settings.invertedBinaryHabits,
        customInputHabits = settings.customInputHabits,
        customInputAmounts = settings.customInputAmounts,
        textInputHabits = settings.textInputHabits,
        textInputOptionsHabits = settings.textInputOptionsHabits,
        sharableTextHabits = settings.sharableTextHabits,
        textInputFileUris = settings.textInputFileUris,
        datedEntryHabits = settings.datedEntryHabits,
        datedEntryFileUris = settings.datedEntryFileUris,
        habitDividers = settings.habitDividers,
        conditionalHabits = settings.conditionalHabits,
        conditionalLinkedHabits = settings.conditionalLinkedHabits,
        conditionalLinkValues = settings.conditionalLinkValues,
        conditionalLinkAmounts = settings.conditionalLinkAmounts,
        conditionalFeedMaxOneHabits = settings.conditionalFeedMaxOneHabits,
        conditionalFeedPointsHabits = settings.conditionalFeedPointsHabits,
        subtypedHabits = settings.subtypedHabits,
        habitSubtypes = settings.habitSubtypes,
        allHabitNames = viewModel.getAllHabitNames(),
        rollForwardHabits = settings.rollForwardHabits,
        rollForwardManualDates = settings.rollForwardManualDates,
        onAddHabit = onAddHabit,
        onAddAppLink = onAddAppLink,
        onAddScreen = onShowAddScreen,
        onDeleteScreen = { viewModel.deleteScreen(activeScreenIndex) },
        onToggleMaxOne = onToggleMaxOne,
        onToggleInvertedBinary = { name -> viewModel.toggleInvertedBinary(name) },
        onToggleCustomInput = { name -> viewModel.toggleCustomInput(name) },
        onSetCustomInputAmounts = { name, amounts -> viewModel.setCustomInputAmounts(name, amounts) },
        onToggleTextInput = { name -> viewModel.toggleTextInput(name) },
        onToggleTextInputOptions = { name -> viewModel.toggleTextInputOptions(name) },
        onEditTextInputOptions = { name ->
            optionsEditorHabit = name
            reloadOptionsEditor(name)
        },
        onToggleSharableText = { name -> viewModel.toggleSharableText(name) },
        onPickTextInputFile = onPickTextInputFile,
        onCreateTextInputFile = onCreateTextInputFile,
        onToggleDatedEntry = { name -> viewModel.toggleDatedEntry(name) },
        onPickDatedEntryFile = onPickDatedEntryFile,
        onRefreshDatedEntry = { name -> viewModel.previewDatedEntryRefresh(name) },
        onDeleteHabit = onDeleteHabit,
        onChangeIcon = onChangeIcon,
        onSetCount = { name, count -> viewModel.setHabitCount(name, count) },
        onSetCountWithRollForward = { name, count, endDate -> viewModel.setHabitCountWithRollForward(name, count, endDate) },
        onSetMinutesCount = { name, count -> viewModel.setHabitMinutesCount(name, count) },
        selectedHabitMinutesTodayCount = selectedHabitName?.let {
            viewModel.getMinutesTodayCount(it)
        } ?: 0,
        minutesFallbackHabits = settings.secondaryValueFallbackHabits,
        onToggleMinutesFallback = { name -> viewModel.toggleMinutesFallbackHabit(name) },
        minutesPrimaryFallbacks = settings.minutesPrimaryFallbacks,
        onSetMinutesPrimaryFallback = { name, source ->
            viewModel.setMinutesPrimaryFallback(name, source)
        },
        onSetDivider = { name, divisor, onGarminHistoryPrompt ->
            viewModel.setHabitDivider(name, divisor, onGarminHistoryPrompt)
        },
        onRecalculateGarminHistory = { name ->
            viewModel.reapplyGarminHistoryForHabit(name)
        },
        onToggleConditional = { name -> viewModel.toggleConditional(name) },
        onToggleConditionalFeedMaxOne = { name -> viewModel.toggleConditionalFeedMaxOne(name) },
        onToggleConditionalFeedPoints = { name -> viewModel.toggleConditionalFeedPoints(name) },
        onSetConditionalLinks = onSetConditionalLinks,
        onBackfillConditional = onBackfillConditional,
        onToggleSubtyped = { name -> viewModel.toggleSubtyped(name) },
        onSetSubtypes = { name, types -> viewModel.setHabitSubtypes(name, types) },
        sleepHabits = settings.sleepHabits,
        sleepHabitVariants = settings.sleepHabitVariants,
        onSetSleepVariant = { name, variant -> viewModel.setSleepVariant(name, variant) },
        mealHabits = settings.mealHabits,
        onToggleMeal = { name -> viewModel.toggleMealHabit(name) },
        weightsHabits = settings.weightsHabits,
        onToggleWeights = { name -> viewModel.toggleWeightsHabit(name) },
        weightsDayValues = selectedHabitName
            ?.takeIf { it in settings.weightsHabits }
            ?.let { viewModel.getWeightsDayValues(it) },
        weightsUnit = settings.graphWeightUnit,
        onSetWeightsDayValues = { name, values, exerciseName ->
            viewModel.setWeightsDayValues(name, values, exerciseName)
        },
        weightsRecentExercises = selectedHabitName
            ?.let { settings.weightsRecentExercises[it] } ?: emptyList(),
        weightsDayExerciseNames = weightsDayExerciseNames,
        onDeleteWeightsDay = { name -> viewModel.deleteWeightsDay(name) },
        onOpenMealDetails = onOpenMealDetails,
        timelineExcludedHabits = settings.timelineExcludedHabits,
        onToggleTimelineExcluded = { name -> viewModel.toggleTimelineExcluded(name) },
        cameraHabits = settings.cameraHabits,
        onToggleCamera = { name -> viewModel.toggleCameraHabit(name) },
        habitLongPressActions = settings.habitLongPressActions,
        onSetLongPressAction = { name, action ->
            viewModel.setHabitLongPressAction(name, action)
        },
        habitLongPressUrls = settings.habitLongPressUrls,
        onSetLongPressUrl = { name, url ->
            viewModel.setHabitLongPressUrl(name, url)
        },
        habitLongPressUrlApps = settings.habitLongPressUrlApps,
        onPickLongPressUrlApp = onPickLongPressUrlApp,
        onClearLongPressUrlApp = { name -> viewModel.setHabitLongPressUrlApp(name, null) },
        hiddenScreenIds = settings.hiddenScreens,
        onToggleScreenHidden = { viewModel.toggleScreenHidden(activeScreenIndex) },
        disabledHabits = settings.disabledHabits,
        onToggleDisabled = { name -> viewModel.toggleDisabledHabit(name) },
        noPointsHabits = settings.noPointsHabits,
        onToggleNoPoints = { name -> viewModel.toggleNoPointsHabit(name) },
        secondaryValueSettings = SecondaryValueSettings(
            habits = settings.secondaryValueHabits,
            onToggleSecondaryValue = { name -> viewModel.toggleSecondaryValueHabit(name) },
            fallbackHabits = settings.secondaryValueFallbackHabits,
            onToggleSecondaryValueFallback = { name -> viewModel.toggleSecondaryValueFallbackHabit(name) }
        ),
        valueDisplayLabels = settings.valueDisplayLabels,
        onSetValueDisplayLabel = { name, key, label ->
            viewModel.setValueDisplayLabel(name, key, label)
        },
        chessComEnabled = settings.chessComEnabled,
        chessComHabitLinks = settings.chessComHabitLinks,
        onSetChessComLink = { name, type -> viewModel.setChessComHabitLink(name, type) },
        garminEnabled = settings.garminEnabled,
        garminHabitLinks = settings.garminHabitLinks,
        onSetGarminLink = { name, type -> viewModel.setGarminHabitLink(name, type) },
        environmentCaptureEnabled = settings.environmentEnabled,
        environmentHabitMetrics = settings.environmentHabitMetrics,
        environmentUseFahrenheit = settings.environmentTemperatureUnit == "F",
        onSetEnvironmentMetric = { name, metric -> viewModel.setEnvironmentHabitMetric(name, metric) },
        envDisplayCount = selectedHabitName?.let { name ->
            settings.environmentHabitMetrics[name]
                ?.let { com.example.tail.data.environment.EnvironmentMetric.fromKey(it) }
                ?.rounded(selectedHabitTodayCount)?.toString()
        },
        garminDateOfBirth = settings.garminDateOfBirth,
        githubContent = {
            if (settings.githubEnabled && selectedHabitName != null) {
                GitHubLinkToggleSection(
                    habitName = selectedHabitName,
                    repoUrls = settings.githubRepoUrls,
                    metrics = settings.githubMetrics,
                    syncStatus = githubSyncStatus,
                    onSetRepoUrl = { url -> viewModel.setGithubRepoUrl(selectedHabitName, url) },
                    onSetMetric = { metric -> viewModel.setGithubMetric(selectedHabitName, GitHubMetric.fromKey(metric)) },
                    onRefetch = { viewModel.fetchGithubBacklog(selectedHabitName) }
                )
            }
        },
        movieBridgeContent = {
            if (settings.bridgeEnabled && selectedHabitName != null &&
                selectedHabitName in settings.textInputHabits
            ) {
                MovieBridgeToggleSection(
                    isMovieLinked = selectedHabitName in settings.bridgeMovieHabits,
                    onToggle = { viewModel.toggleBridgeMovieHabit(selectedHabitName) }
                )
            }
        },
        pcWidgetContent = {
            if (selectedHabitName != null) {
                androidx.compose.foundation.layout.Column {
                    PcWidgetToggleSection(
                        isOnPcWidget = selectedHabitName in settings.pcWidgetHabits,
                        syncConfigured = settings.garminProxyUrl.isNotEmpty(),
                        onToggle = { viewModel.togglePcWidgetHabit(selectedHabitName) }
                    )
                    LockWidgetToggleSection(
                        isIncluded = selectedHabitName !in settings.lockWidgetExcludedHabits,
                        onToggle = { viewModel.toggleLockWidgetHabit(selectedHabitName) }
                    )
                }
            }
        },
        garminMonthlyData = garminMonthlyData,
        selectedDate = selectedDate,
        voiceTriggerEnabled = settings.voiceTriggerEnabled,
        voiceTriggerHabits = settings.voiceTriggerHabits,
        voiceTriggerWords = settings.voiceTriggerWords,
        voiceTriggerIncrements = settings.voiceTriggerIncrements,
        onToggleVoiceTrigger = { name -> viewModel.toggleVoiceTrigger(name) },
        onSetVoiceTriggerWords = { name, words -> viewModel.setVoiceTriggerWords(name, words) },
        onSetVoiceTriggerIncrement = { name, amount -> viewModel.setVoiceTriggerIncrement(name, amount) },
        voiceSubtypeHabits = settings.voiceSubtypeHabits,
        onToggleVoiceSubtype = { name -> viewModel.toggleVoiceSubtype(name) },
        timelessHabits = settings.timelessHabits,
        onToggleTimeless = { name -> viewModel.toggleTimeless(name) },
        customPointRangesHabits = settings.customPointRangesHabits,
        customPointRanges = settings.customPointRanges,
        onToggleCustomPointRanges = { name -> viewModel.toggleCustomPointRanges(name) },
        onSetCustomPointRanges = { name, ranges -> viewModel.setCustomPointRanges(name, ranges) },
        selectedHabitTimestampCount = selectedHabitTimestampCount,
        onShowTimestamps = { name ->
            showTimestampsForHabit(
                name = name,
                viewModel = viewModel,
                selectedDate = selectedDate,
                settings = settings,
                scope = timestampScope,
                setList = setTimestampList,
                setMinutes = setTimestampMinutes,
                setHabitName = setTimestampHabit,
                setTextEntries = setEditModeTextEntries
            )
        },
        todayTextEntries = editModeTextEntries,
        onLoadTextEntries = { name, onResult ->
            viewModel.loadTextEntriesWithTimestamps(name, selectedDate, onResult)
        },
        onEditTextEntry = { name, timestamp, newText ->
            editModeEditEntry(
                name = name,
                timestamp = timestamp,
                newText = newText,
                viewModel = viewModel,
                settings = settings,
                selectedDate = selectedDate,
                setTextEntries = setEditModeTextEntries,
                onShowRollForward = onShowRollForward
            )
        },
        onAddTextEntry = { name, newText ->
            editModeAddEntry(
                name = name,
                newText = newText,
                viewModel = viewModel,
                settings = settings,
                selectedDate = selectedDate,
                setTextEntries = setEditModeTextEntries,
                onShowRollForward = onShowRollForward
            )
        },
        onDeleteTextEntry = { name, timestamp ->
            viewModel.deleteTextEntry(name, timestamp) {
                // Reload entries after delete completes so the
                // removed row vanishes instantly from the list.
                viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
                    setEditModeTextEntries(entries)
                }
            }
        },
        habitNotes = settings.habitNotes,
        onSetHabitNote = { name, note -> viewModel.setHabitNote(name, note) },
        onToggleRollForward = { name -> viewModel.toggleRollForward(name) },
        habitScheduleTimes = settings.habitScheduleTimes,
        onSetHabitScheduleTime = { name, time ->
            viewModel.setHabitScheduleTime(name, time)
        },
        onRestoreFromBackup = onRestoreFromBackup,
        onRenameHabit = { oldName, newName -> viewModel.renameHabit(oldName, newName) },
        habitAppAssociations = settings.habitAppAssociations,
        onAddAppAssociation = onPickAppAssociation,
        onRemoveAppAssociation = { name, pkg -> viewModel.removeHabitAppAssociation(name, pkg) },
        onMoveAppAssociation = { name, from, to -> viewModel.moveHabitAppAssociation(name, from, to) },
        widgetTriggerHabits = settings.widgetTriggerHabits,
        widgetTriggerApps = settings.widgetTriggerApps,
        onToggleWidgetTrigger = { name -> viewModel.toggleWidgetTrigger(name) },
        onSetWidgetTriggerApp = onPickWidgetTriggerApp,
        widgetPersistentTimerHabits = settings.widgetPersistentTimerHabits,
        onTogglePersistentTimer = { name -> viewModel.toggleWidgetPersistentTimer(name) },
        bubbleFullScreenApps = settings.bubbleFullScreenApps,
        onToggleFullScreenMenu = { name, enabled ->
            viewModel.setBubbleFullScreenMenu(name, enabled)
        },
        bubbleMultiTimerApps = settings.bubbleMultiTimerApps,
        onToggleMultiTimer = { name, enabled ->
            viewModel.setBubbleMultiTimer(name, enabled)
        },
        hasUsageAccess = viewModel.hasUsageAccess(),
        onRequestUsageAccess = { viewModel.openUsageAccessSettings() },
        widgetTimerMinutesPrimary = settings.widgetTimerMinutesPrimary,
        onSetTimerPrimaryValue = { name, minutesPrimary ->
            viewModel.setWidgetTimerPrimaryValue(name, minutesPrimary)
        },
        minutesEnabled = selectedHabitName?.let {
            viewModel.isMinutesEnabled(it)
        } ?: false,
        minutesForcedByWidget = selectedHabitName?.let {
            viewModel.isMinutesForcedByWidget(it)
        } ?: false,
        onToggleMinutesEnabled = { name -> viewModel.toggleMinutesEnabled(name) },
        mediaHabits = settings.mediaHabits,
        mediaApps = settings.mediaApps,
        onToggleMedia = { name -> viewModel.toggleMediaHabit(name) },
        onSetMediaApp = onPickMediaApp,
        hasNotificationAccess = viewModel.hasNotificationListenerAccess(),
        onRequestNotificationAccess = { viewModel.openNotificationListenerSettings() },
        mediaTodayShows = viewModel.mediaTodayShows.collectAsState().value,
        onLoadMediaShows = { name -> viewModel.loadMediaTodayShows(name) },
        onRemoveMediaShow = { name, show -> viewModel.removeMediaShowFromToday(name, show) },
        onInvertHabit = { name -> viewModel.invertHabit(name) },
        onGetInvertPreview = { name -> viewModel.getInvertPreview(name) }
    )

    // ── Text-input options editor popup (large, retro-rename + descriptions) ──
    val editorHabit = optionsEditorHabit
    if (editorHabit != null) {
        TextInputOptionsEditorDialog(
            habitName = editorHabit,
            singles = optionsEditorSingles,
            groupings = optionsEditorGroupings,
            hiddenGroupings = settings.textInputHiddenGroupings[editorHabit]?.toList() ?: emptyList(),
            descriptions = settings.textInputOptionDescriptions[editorHabit] ?: emptyMap(),
            onRename = { oldText, newText ->
                viewModel.renameTextOption(editorHabit, oldText, newText) {
                    reloadOptionsEditor(editorHabit)
                }
            },
            onSetDescription = { optionText, description ->
                viewModel.setTextOptionDescription(editorHabit, optionText, description)
            },
            onHideGrouping = { grouping ->
                viewModel.hideTextOptionGrouping(editorHabit, grouping)
            },
            onUnhideGrouping = { grouping ->
                viewModel.unhideTextOptionGrouping(editorHabit, grouping)
            },
            onDismiss = { optionsEditorHabit = null }
        )
    }
}
