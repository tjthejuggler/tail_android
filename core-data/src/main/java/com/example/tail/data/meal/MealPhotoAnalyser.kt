package com.example.tail.data.meal

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tail.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "MealPhotoAnalyser"

/**
 * Result of an in-editor photo analysis.
 *
 * @param imagePath Relative path (within [Context.filesDir]) of the stored
 *        photo copy — always set when the image could be read, even when the
 *        LLM call failed (the photo is still attached to the meal on save).
 * @param foodData Parsed meal specifics from the LLM (null when the LLM was
 *        not configured, failed, or saw no food).
 * @param error Human-readable failure note (null on success).
 */
data class MealPhotoAnalysis(
    val imagePath: String?,
    val foodData: FoodData?,
    val error: String?
)

/**
 * One-shot synchronous photo → LLM analysis used by the meal editor's
 * "📷 Camera (AI)" / "🖼️ Upload (AI)" actions.
 *
 * Unlike the background [VisionProcessingWorker] queue (which persists
 * results on its own), this returns the parsed [FoodData] to the CALLER so
 * the open editor can fill its fields — nothing is persisted until the user
 * taps Save. Mirrors the config handling of the voice path
 * ([HabitViewModel.processVoiceMeal]).
 */
object MealPhotoAnalyser {

    /** Analyses a gallery-picked image: copies it into internal storage first. */
    suspend fun analyseUri(context: Context, uri: Uri): MealPhotoAnalysis =
        withContext(Dispatchers.IO) {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read picked image", e)
                null
            }
            if (bytes == null || bytes.isEmpty()) {
                return@withContext MealPhotoAnalysis(null, null, "Could not read the image")
            }
            val relPath = MealLogRepository(context).saveImageBytes(bytes)
            analyseFile(context, relPath)
        }

    /**
     * Analyses an image already stored in internal storage (e.g. captured
     * straight into `files/meal_images/` via the camera FileProvider).
     */
    suspend fun analyseFile(context: Context, relPath: String): MealPhotoAnalysis =
        withContext(Dispatchers.IO) {
            val file = MealLogRepository(context).resolveImage(relPath)
            if (file == null) {
                return@withContext MealPhotoAnalysis(null, null, "Image file missing")
            }

            val s = try {
                SettingsRepository(context).settingsFlow.first()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load settings", e)
                return@withContext MealPhotoAnalysis(relPath, null, "Settings unavailable")
            }
            if (!s.mealEnabled || s.mealApiKey.isBlank() ||
                s.mealBaseUrl.isBlank() || s.mealModel.isBlank()
            ) {
                return@withContext MealPhotoAnalysis(
                    relPath, null, "AI not configured (Settings → Meal Engine)"
                )
            }

            val config = VisionConfig(
                baseUrl = s.mealBaseUrl,
                apiKey = s.mealApiKey,
                model = s.mealModel,
                userSystemPrompt = s.mealSystemPrompt
            )
            val result = try {
                VisionProcessingService().processImage(file, config)
            } catch (e: Exception) {
                Log.e(TAG, "Photo analysis failed", e)
                null
            }
            when {
                result == null -> MealPhotoAnalysis(relPath, null, "AI request failed — check connection")
                result.foodData == null -> MealPhotoAnalysis(
                    relPath,
                    null,
                    result.processingNotes.take(80).ifBlank { "No food recognised" }
                )
                else -> MealPhotoAnalysis(relPath, result.foodData, null)
            }
        }

    /**
     * Result of an in-editor voice analysis (see [analyseVoice]).
     *
     * @param foodData Parsed meal specifics from the LLM (null when the LLM
     *        was not configured or the request failed).
     * @param error Human-readable failure note (null on success).
     */
    data class MealVoiceAnalysis(
        val foodData: FoodData?,
        val error: String?
    )

    /**
     * One-shot voice → LLM nutrition analysis used by the meal editor's mic
     * button. Sends the transcript alone — or together with the meal's first
     * attached photo when one exists — and returns the parsed [FoodData] to
     * the CALLER so the open editor can fill its fields. Nothing is
     * persisted until the user taps Save.
     */
    suspend fun analyseVoice(
        context: Context,
        transcript: String,
        imageRelPath: String?
    ): MealVoiceAnalysis = withContext(Dispatchers.IO) {
        if (transcript.isBlank()) {
            return@withContext MealVoiceAnalysis(null, "Nothing was said")
        }
        val s = try {
            SettingsRepository(context).settingsFlow.first()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings", e)
            return@withContext MealVoiceAnalysis(null, "Settings unavailable")
        }
        if (!s.mealEnabled || s.mealApiKey.isBlank() ||
            s.mealBaseUrl.isBlank() || s.mealModel.isBlank()
        ) {
            return@withContext MealVoiceAnalysis(null, "AI not configured (Settings → Meal Engine)")
        }

        val config = VisionConfig(
            baseUrl = s.mealBaseUrl,
            apiKey = s.mealApiKey,
            model = s.mealModel,
            userSystemPrompt = s.mealSystemPrompt
        )
        val imageFile = imageRelPath?.takeIf { it.isNotBlank() }
            ?.let { MealLogRepository(context).resolveImage(it) }
        val fd = try {
            VisionProcessingService().processMealText(transcript, config, imageFile)
        } catch (e: Exception) {
            Log.e(TAG, "Voice analysis failed", e)
            null
        }
        if (fd != null) MealVoiceAnalysis(fd, null)
        else MealVoiceAnalysis(null, "AI could not parse the description")
    }
}
