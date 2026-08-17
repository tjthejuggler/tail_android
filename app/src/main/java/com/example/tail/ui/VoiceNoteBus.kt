package com.example.tail.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process bus for voice-note saves.
 *
 * Emitted by [com.example.tail.ipc.SmartVoiceService] after a spoken note has
 * been written to the notes file, so screens hosting the voice session
 * (e.g. [com.example.tail.MediaCaptureActivity]) can close themselves as soon
 * as the voice input is complete — mirroring [HabitIncrementBus] for the
 * note-routing branch of smart voice.
 */
object VoiceNoteBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Call after a voice note is saved to notify the UI layer. */
    fun emit(noteText: String) {
        _events.tryEmit(noteText)
    }
}
