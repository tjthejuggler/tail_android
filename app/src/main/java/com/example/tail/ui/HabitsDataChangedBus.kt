package com.example.tail.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process signal that the habits database was mutated by the app itself
 * (HabitViewModel increments / count edits / deletions).
 *
 * Deliberately separate from [HabitIncrementBus]: the ViewModel *collects*
 * that bus to reload its whole UI, so emitting there from its own mutation
 * paths would trigger a redundant heavy self-reload on every tap. This bus
 * is for lightweight listeners that just want to know "the file changed" —
 * the stats overlay service, which re-reads the DB and updates its numbers
 * within ~150 ms.
 *
 * Emitters MUST call [emit] only AFTER the write has been persisted to disk,
 * so listeners that re-read the file never see stale data.
 */
object HabitsDataChangedBus {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 16)
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    /** Call after any in-app write that changed habit counts. */
    fun emit() {
        _events.tryEmit(Unit)
    }
}
