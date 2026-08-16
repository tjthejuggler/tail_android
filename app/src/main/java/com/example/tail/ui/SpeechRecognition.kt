package com.example.tail.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

private const val TAG = "MealSpeech"

/**
 * Host for a single [SpeechRecognizer] instance used by the meal screens to
 * record a spoken meal description ("just say what was eaten"). Exposes
 * [isListening] state and start/stop controls; results and errors arrive
 * through the callbacks given to [rememberSpeechRecognizer].
 *
 * The caller is responsible for holding the RECORD_AUDIO runtime permission
 * before calling [start] (the meal screen requests it via a launcher).
 */
class SpeechRecognizerHost(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningChange: (Boolean) -> Unit
) {
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
        set(value) {
            field = value
            onListeningChange(value)
        }

    private fun ensureRecognizer(): SpeechRecognizer {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) { listening = true }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        listening = false
                        onError(humanError(error))
                    }
                    override fun onResults(results: Bundle?) {
                        listening = false
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull { it.isNotBlank() }
                        if (text != null) onResult(text) else onError("No speech heard")
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
        return recognizer!!
    }

    /** Starts a fresh listening session (stops any previous one first). */
    fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not available on this device")
            return
        }
        stop()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe the meal…")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        ensureRecognizer().startListening(intent)
        listening = true
    }

    /** Stops listening without destroying the recognizer. */
    fun stop() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        listening = false
    }

    /** Toggles between start and stop. */
    fun toggle() {
        if (listening) stop() else start()
    }

    /** Releases the underlying recognizer (call from onDispose). */
    fun destroy() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        listening = false
    }

    private fun humanError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech heard"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard (timeout)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
        else -> "Voice error ($error)"
    }
}

/**
 * Remembers a [SpeechRecognizerHost] for the duration of the composition.
 * [isListening] reacts to the recognizer's state so buttons can show it.
 */
@androidx.compose.runtime.Composable
fun rememberSpeechRecognizer(
    onResult: (String) -> Unit,
    onError: (String) -> Unit
): Pair<SpeechRecognizerHost, Boolean> {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isListening by androidx.compose.runtime.remember { mutableStateOf(false) }
    val host = remember {
        SpeechRecognizerHost(
            context = context.applicationContext,
            onResult = onResult,
            onError = onError,
            onListeningChange = { isListening = it }
        )
    }
    DisposableEffect(Unit) {
        onDispose { host.destroy() }
    }
    return host to isListening
}
