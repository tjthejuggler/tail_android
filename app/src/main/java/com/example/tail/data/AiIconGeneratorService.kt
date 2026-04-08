package com.example.tail.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Metadata for an available image generation model. */
data class AiModelInfo(
    val id: String,
    val name: String,
    val qualities: List<String> = emptyList(),
    val pricing: String = ""
)

/** Hardcoded fallback list of known image models (used when API fetch fails or returns unexpected format). */
val FALLBACK_IMAGE_MODELS = listOf(
    AiModelInfo("gpt-image-1", "GPT Image 1 (4o)", listOf("low", "medium", "high")),
    AiModelInfo("gpt-image-1.5", "GPT Image 1.5", listOf("medium", "high")),
    AiModelInfo("nano-banana-pro", "Gemini 3.0", listOf("standard", "4k")),
    AiModelInfo("flux-2-pro", "Flux 2 Pro", listOf("1k", "2k")),
    AiModelInfo("flux-2-flex", "Flux 2 Flex", listOf("1k", "2k")),
    AiModelInfo("flux-kontext-pro", "Flux Kontext Pro", emptyList()),
    AiModelInfo("flux-kontext-max", "Flux Kontext Max", emptyList())
)

/**
 * Calls an OpenAI-compatible image generation API and post-processes the result
 * into a white-on-transparent icon suitable for the habit grid.
 *
 * Supports APIs that return either:
 *   - `b64_json` (base64-encoded image data)
 *   - `url` (a URL to download the image from)
 */
class AiIconGeneratorService {

    /**
     * Generates an icon from the given prompt using the configured API.
     *
     * @param prompt Text description of the icon to generate.
     * @param apiKey Bearer token for the API.
     * @param baseUrl Base URL (e.g. "https://api.ppq.ai").
     * @param endpoint Endpoint path (e.g. "/v1/images/generations").
     * @param model Model name (e.g. "flux-2-pro").
     * @param quality Quality tier (e.g. "standard", "1k", "2k", "medium", "high"). Empty = omit.
     * @return A white-on-transparent Bitmap ready for use as a habit icon.
     */
    suspend fun generateIcon(
        prompt: String,
        apiKey: String,
        baseUrl: String,
        endpoint: String,
        model: String,
        quality: String = ""
    ): Bitmap = withContext(Dispatchers.IO) {
        val fullUrl = baseUrl.trimEnd('/') + "/" + endpoint.trimStart('/')
        Log.i("AiIconGen", "Requesting icon from $fullUrl model=$model")

        // Build the icon-specific prompt that asks for a simple white icon
        // matching the existing clipart/silhouette style of the built-in icons.
        // CRITICAL: We emphasize pure black background (#000000) so post-processing
        // can reliably strip it to transparent.
        val iconPrompt = "Generate a single solid white (#FFFFFF) silhouette icon on a " +
                "PURE BLACK (#000000) background. The ENTIRE background must be solid black " +
                "with absolutely NO white, gray, or light areas in the background. " +
                "Flat 2D clipart style, no gradients, no shading, no 3D effects, no perspective, " +
                "no shadows, no glow, no reflections. " +
                "Thick bold outlines filled solid white. No text, no labels, no watermarks. " +
                "Centered in frame with generous padding around edges. " +
                "Simple recognizable shape like a classic toolbar icon or emoji. " +
                "The icon must be WHITE and the background must be BLACK. " +
                "Subject: $prompt"

        val requestBody = JSONObject().apply {
            put("model", model)
            put("prompt", iconPrompt)
            put("n", 1)
            if (quality.isNotEmpty()) {
                put("quality", quality)
            }
        }

        val connection = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = 60_000
            readTimeout = 120_000
            doOutput = true
        }

        try {
            connection.outputStream.use { out ->
                out.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                // Truncate HTML error pages to just the first 200 chars
                val shortError = if (errorText.length > 200) errorText.take(200) + "…" else errorText
                throw RuntimeException("API returned $responseCode: $shortError")
            }

            val responseJson = JSONObject(responseText)
            val dataArray = responseJson.getJSONArray("data")
            val firstItem = dataArray.getJSONObject(0)

            val rawBitmap: Bitmap = if (firstItem.has("b64_json")) {
                val b64 = firstItem.getString("b64_json")
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw RuntimeException("Failed to decode base64 image")
            } else if (firstItem.has("url")) {
                val imageUrl = firstItem.getString("url")
                val imageBytes = URL(imageUrl).readBytes()
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    ?: throw RuntimeException("Failed to decode image from URL")
            } else {
                throw RuntimeException("API response has no image data (no b64_json or url)")
            }

            // Post-process: convert to white-on-transparent
            postProcessToWhiteIcon(rawBitmap)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Fetches available image models from the API.
     * @return List of AiModelInfo with model id, name, quality options, and pricing.
     */
    suspend fun fetchModels(
        apiKey: String,
        baseUrl: String
    ): List<AiModelInfo> = withContext(Dispatchers.IO) {
        val fullUrl = baseUrl.trimEnd('/') + "/v1/media/models"
        Log.i("AiIconGen", "Fetching models from $fullUrl")

        val connection = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = 15_000
            readTimeout = 15_000
        }

        try {
            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
                val shortError = if (errorText.length > 200) errorText.take(200) + "…" else errorText
                throw RuntimeException("API returned $responseCode: $shortError")
            }

            val json = JSONObject(responseText)
            val models = mutableListOf<AiModelInfo>()

            // Try parsing as { "data": [...] } or as a direct array or object with model keys
            val dataArray: JSONArray? = json.optJSONArray("data")
            if (dataArray != null) {
                for (i in 0 until dataArray.length()) {
                    val m = dataArray.getJSONObject(i)
                    val id = m.optString("id", m.optString("model", ""))
                    if (id.isEmpty()) continue
                    val name = m.optString("name", id)
                    val pricing = m.optString("pricing", "")
                    val qualityArr = m.optJSONArray("quality")
                    val qualities = mutableListOf<String>()
                    if (qualityArr != null) {
                        for (q in 0 until qualityArr.length()) {
                            qualities.add(qualityArr.getString(q))
                        }
                    }
                    models.add(AiModelInfo(id = id, name = name, qualities = qualities, pricing = pricing))
                }
            }

            // If no models parsed from data array, return hardcoded fallback for image models
            if (models.isEmpty()) {
                models.addAll(FALLBACK_IMAGE_MODELS)
            }

            models
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Post-processes a raw AI-generated image into a white-on-transparent icon.
     *
     * Strategy:
     * 1. Sample edge pixels to auto-detect whether the background is light or dark
     * 2. If background is dark (expected): bright pixels = icon → white, dark = transparent
     * 3. If background is light (model ignored prompt): dark pixels = icon → white, light = transparent
     * This ensures we always get a white-on-transparent result regardless of what the model produces.
     */
    companion object {
        private fun luminance(pixel: Int): Int {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
        }

        fun postProcessToWhiteIcon(source: Bitmap): Bitmap {
            val width = source.width
            val height = source.height
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)

            // Sample edge pixels (top row, bottom row, left col, right col) to detect bg color
            val edgeLuminances = mutableListOf<Int>()
            for (x in 0 until width) {
                edgeLuminances.add(luminance(pixels[x]))                          // top row
                edgeLuminances.add(luminance(pixels[(height - 1) * width + x]))   // bottom row
            }
            for (y in 1 until height - 1) {
                edgeLuminances.add(luminance(pixels[y * width]))                  // left col
                edgeLuminances.add(luminance(pixels[y * width + width - 1]))      // right col
            }
            val avgEdgeLuminance = if (edgeLuminances.isNotEmpty()) {
                edgeLuminances.sum() / edgeLuminances.size
            } else 0

            // If average edge luminance > 128, the background is light (model gave white bg)
            // In that case, DARK pixels are the icon; we invert the logic
            val bgIsLight = avgEdgeLuminance > 128

            Log.i("AiIconGen", "Post-process: avgEdgeLuminance=$avgEdgeLuminance bgIsLight=$bgIsLight")

            val result = IntArray(width * height)

            for (i in pixels.indices) {
                val lum = luminance(pixels[i])

                val alpha = if (bgIsLight) {
                    // Background is light → dark pixels are the icon
                    // Invert: low luminance = high alpha (icon), high luminance = transparent (bg)
                    when {
                        lum < 60  -> 255
                        lum < 180 -> (255 - (lum - 60) * 255 / 120).coerceIn(0, 255)
                        else      -> 0
                    }
                } else {
                    // Background is dark (expected) → bright pixels are the icon
                    when {
                        lum > 200 -> 255
                        lum > 80  -> ((lum - 80) * 255 / 120).coerceIn(0, 255)
                        else      -> 0
                    }
                }

                result[i] = Color.argb(alpha, 255, 255, 255)
            }

            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            output.setPixels(result, 0, width, 0, 0, width, height)

            // Scale down to 64x64 for consistency with existing icons
            val scaled = Bitmap.createScaledBitmap(output, 64, 64, true)
            if (scaled !== output) output.recycle()
            source.recycle()

            return scaled
        }
    }
}
