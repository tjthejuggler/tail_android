package com.example.tail.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tail.data.AdviceItem
import com.example.tail.data.AdviceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AdviceUiState(
    /** All advice items. */
    val items: List<AdviceItem> = emptyList(),
    /** Current index for the banner display. */
    val currentIndex: Int = 0,
    /** History of shown indices (for swipe-back). */
    val history: List<Int> = emptyList()
)

class AdviceViewModel(private val repo: AdviceRepository) : ViewModel() {

    private val _state = MutableStateFlow(AdviceUiState())
    val state: StateFlow<AdviceUiState> = _state

    init {
        viewModelScope.launch {
            repo.observeAll().collect { list ->
                _state.update { old ->
                    val idx = old.currentIndex
                    val newIdx = if (list.isEmpty()) 0 else idx.coerceIn(0, list.size - 1)
                    old.copy(items = list, currentIndex = newIdx)
                }
            }
        }
    }

    /** Called on screen entry – picks a fresh random index. */
    fun randomizeOnEntry() {
        _state.update { old ->
            if (old.items.isEmpty()) return@update old
            val newIdx = (0 until old.items.size).random()
            old.copy(currentIndex = newIdx)
        }
    }

    /** Swipe right → show next random advice (not the same as current). */
    fun nextRandom() {
        _state.update { old ->
            if (old.items.size <= 1) return@update old
            var newIdx: Int
            do {
                newIdx = (0 until old.items.size).random()
            } while (newIdx == old.currentIndex)
            old.copy(
                currentIndex = newIdx,
                history = old.history + old.currentIndex
            )
        }
    }

    /** Swipe left → go back to previously shown advice. */
    fun previous() {
        _state.update { old ->
            if (old.history.isEmpty()) return@update old
            val prevIdx = old.history.last()
            old.copy(
                currentIndex = prevIdx,
                history = old.history.dropLast(1)
            )
        }
    }

    // ── Settings dialog operations ────────────────────────────────────────────

    fun addAdvice(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { repo.add(text.trim()) }
    }

    fun updateAdvice(entity: AdviceItem, newText: String) {
        if (newText.isBlank()) return
        viewModelScope.launch { repo.update(entity.copy(text = newText.trim())) }
    }

    fun deleteAdvice(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }

    // ── Notes operations ──────────────────────────────────────────────────────

    /** Save notes for a specific advice item. Empty/blank → null. */
    fun saveNotes(adviceId: Long, notes: String) {
        val trimmed = notes.trim().ifBlank { null }
        viewModelScope.launch { repo.updateNotes(adviceId, trimmed) }
    }
}

class AdviceViewModelFactory(private val repo: AdviceRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AdviceViewModel(repo) as T
    }
}
