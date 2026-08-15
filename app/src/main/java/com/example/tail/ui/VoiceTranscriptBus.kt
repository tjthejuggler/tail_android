package com.example.tail.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process bus that delivers a speech transcript from
 * [com.example.tail.ipc.SmartVoiceService] to
 * [com.example.tail.MediaCaptureActivity] in **tandem mode** (hold the
 * capture button → photo + voice are paired).
 *
 * Flow:
 *  1. The user long-presses the capture button → the activity takes the
 *     photo and calls [armTandem].
 *  2. The service (already listening) receives speech results. Because
 *     tandem is armed, it does NOT route the text itself — it emits the
 *     transcript here and stops.
 *  3. The activity collects the transcript and sends photo + transcript
 *     to the LLM together as a teaching example.
 *
 * When tandem is NOT armed, the service behaves exactly as before
 * (smart routing to habits/notes) — existing behaviour is untouched.
 */
object VoiceTranscriptBus {

    /** A transcript delivered while tandem mode was armed. */
    data class TandemTranscript(val transcript: String, val timestamp: Long)

    /** A recognition failure delivered while tandem mode was armed. */
    data class TandemError(val reason: String)

    private val _transcripts = MutableSharedFlow<TandemTranscript>(extraBufferCapacity = 4)
    val transcripts: SharedFlow<TandemTranscript> = _transcripts.asSharedFlow()

    private val _errors = MutableSharedFlow<TandemError>(extraBufferCapacity = 4)
    val errors: SharedFlow<TandemError> = _errors.asSharedFlow()

    /**
     * Set by the capture activity the moment a tandem photo is taken.
     * The voice service checks this flag in onResults/onError to decide
     * whether to route speech itself or hand it over to the capture flow.
     */
    @Volatile
    var tandemArmed: Boolean = false
        private set

    /** Arms tandem mode — subsequent speech results go to [transcripts]. */
    fun armTandem() {
        tandemArmed = true
    }

    /** Disarms tandem mode — speech routing returns to normal. */
    fun disarmTandem() {
        tandemArmed = false
    }

    /** Called by the voice service to deliver a tandem transcript. */
    fun emitTranscript(text: String) {
        _transcripts.tryEmit(TandemTranscript(text, System.currentTimeMillis()))
    }

    /** Called by the voice service to deliver a tandem recognition failure. */
    fun emitError(reason: String) {
        _errors.tryEmit(TandemError(reason))
    }
}
