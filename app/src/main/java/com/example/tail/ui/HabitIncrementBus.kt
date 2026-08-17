package com.example.tail.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process event bus for habit increment notifications.
 *
 * When [SmartVoiceService], [HabitIncrementReceiver], or [ShareTextActivity]
 * increment a habit outside the ViewModel, they emit the habit name here.
 * [HabitViewModel] collects the flow and reloads the DB so the UI updates
 * instantly — no Android broadcast permission headaches.
 */
object HabitIncrementBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    /** Call after an external increment (voice, IPC, share) to notify the UI layer. */
    fun emit(habitName: String) {
        _events.tryEmit(habitName)
    }
}
