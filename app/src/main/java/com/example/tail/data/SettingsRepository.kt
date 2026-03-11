package com.example.tail.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tail_settings")

private val KEY_FILE_URI = stringPreferencesKey("file_uri")
private val KEY_HISTORICAL_FILE_URI = stringPreferencesKey("historical_file_uri")
private val KEY_TOTALS_FILE_URI = stringPreferencesKey("totals_file_uri")
private val KEY_CUSTOM_INPUT = stringSetPreferencesKey("custom_input_habits")

/**
 * Persists app settings (file URIs, custom input habits) using DataStore.
 */
class SettingsRepository(private val context: Context) {

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            fileUri = prefs[KEY_FILE_URI] ?: "",
            historicalFileUri = prefs[KEY_HISTORICAL_FILE_URI] ?: "",
            totalsFileUri = prefs[KEY_TOTALS_FILE_URI] ?: "",
            customInputHabits = prefs[KEY_CUSTOM_INPUT] ?: DEFAULT_CUSTOM_INPUT_HABITS
        )
    }

    suspend fun saveFileUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FILE_URI] = uri
        }
    }

    suspend fun saveHistoricalFileUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HISTORICAL_FILE_URI] = uri
        }
    }

    suspend fun saveTotalsFileUri(uri: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TOTALS_FILE_URI] = uri
        }
    }

    suspend fun saveCustomInputHabits(habits: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CUSTOM_INPUT] = habits
        }
    }
}
