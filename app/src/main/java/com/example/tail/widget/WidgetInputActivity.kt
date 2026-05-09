package com.example.tail.widget

import android.appwidget.AppWidgetManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.ui.TextInputDialog
import com.example.tail.ui.theme.TailTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Transparent trampoline activity launched by the lock-screen habit widget when
 * the user taps a TEXT-INPUT habit. Shows the same [TextInputDialog] as the main
 * app, then on confirm:
 *   1. Appends the text to the habit's text-log file.
 *   2. Increments the habit's count by 1 in the phone DB.
 *   3. Records a widget-local recent-tap so this habit slides to the top.
 *   4. Asks the widget to refresh.
 *
 * Cancelling the dialog finishes without touching anything.
 */
class WidgetInputActivity : ComponentActivity() {

    companion object {
        const val ACTION_SHOW = "com.example.tail.widget.SHOW_INPUT"
        const val EXTRA_HABIT_NAME = "habit_name"
    }

    private val settingsRepo by lazy { SettingsRepository(applicationContext) }
    private val textInputRepo by lazy { TextInputRepository() }
    private val habitsRepo by lazy { HabitsRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val habitName = intent?.getStringExtra(EXTRA_HABIT_NAME)
        val widgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (habitName.isNullOrBlank()) {
            finish()
            return
        }

        setContent {
            TailTheme(darkTheme = true) {
                // null = still loading settings; isEmpty list = options not enabled
                var optionsState by remember { mutableStateOf<List<String>?>(null) }
                var showOptions by remember { mutableStateOf(false) }

                LaunchedEffect(habitName) {
                    val settings = settingsRepo.settingsFlow.first()
                    showOptions = habitName in settings.textInputOptionsHabits
                    val logUri = settings.textInputFileUris[habitName]
                    optionsState = if (showOptions && logUri != null) {
                        try {
                            textInputRepo.loadTextLog(Uri.parse(logUri), applicationContext)
                                .values
                                .toSet()
                                .sorted()
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else {
                        emptyList()
                    }
                }

                // Don't render the dialog until we've finished loading — keeps the
                // transparent surface from flashing an empty Dialog on launch.
                if (optionsState != null) {
                    TextInputDialog(
                        habitName = habitName,
                        showOptions = showOptions,
                        options = optionsState!!,
                        onConfirm = { entry ->
                            saveAndFinish(habitName, widgetId, entry)
                        },
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }

    private fun saveAndFinish(habitName: String, widgetId: Int, text: String) {
        lifecycleScope.launch {
            try {
                val settings = settingsRepo.settingsFlow.first()

                // 1. Append text to the habit's log file (only if non-blank and a URI is set).
                val logUriStr = settings.textInputFileUris[habitName]
                if (text.isNotBlank() && !logUriStr.isNullOrEmpty()) {
                    textInputRepo.appendTextEntry(
                        Uri.parse(logUriStr), applicationContext, text
                    )
                }

                // 2. Increment the habit's count in the phone DB (mirrors ShareTextActivity).
                val phoneUriStr = settings.fileUri
                if (phoneUriStr.isNotEmpty()) {
                    habitsRepo.incrementHabit(
                        Uri.parse(phoneUriStr),
                        applicationContext,
                        habitName,
                        1
                    )
                }

                // 3. Record widget-local recent tap (text-input habits aren't "max-one"
                //    by definition, so they always go to the top).
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetPreferences.recordTap(applicationContext, widgetId, habitName)
                }

                // 4. Tell the widget to refresh.
                HabitListWidgetProvider.refreshAll(applicationContext)
            } catch (e: Exception) {
                Toast.makeText(
                    applicationContext,
                    "Failed to save: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                finish()
            }
        }
    }
}
