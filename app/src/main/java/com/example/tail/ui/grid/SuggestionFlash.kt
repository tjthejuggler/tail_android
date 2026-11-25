package com.example.tail.ui.grid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.AiIconRepository

/**
 * The kind of pre-filled payload a suggestion carries — determines what the
 * one-tap ✓ does and whether the ✎ edit button opens a pre-filled dialog.
 */
enum class SuggestionKind {
    /** Plain tap habit — ✓ increments by [HabitSuggestion.amount]. */
    PLAIN,
    /** Custom-input habit — ✓ increments by the pre-filled recent amount. */
    CUSTOM_AMOUNT,
    /** Text-input habit — ✓ saves the pre-filled text entry (✎ opens the dialog with it typed in). */
    TEXT,
    /** Subtyped habit — ✓ increments the predicted subtype by [HabitSuggestion.amount] (✎ opens the subtype dialog). */
    SUBTYPE,
    /** Weights habit — no one-tap increment; ✎ opens the weights logging dialog. */
    WEIGHTS,
    /** Pill-log habit — ✓ logs the predicted pill bundle ([HabitSuggestion.pills]); ✎ opens the pill dialog. */
    PILLS
}

/**
 * One "likely next increment" suggestion with everything needed to fully
 * increment the habit in a single tap.
 */
data class HabitSuggestion(
    val habitName: String,
    val kind: SuggestionKind,
    /** Increment amount (PLAIN / CUSTOM_AMOUNT; always 1 otherwise). */
    val amount: Int = 1,
    /** Subtype to increment (SUBTYPE). */
    val subtype: String? = null,
    /** Pre-filled text (TEXT). */
    val text: String? = null,
    /** Pill → servings bundle (PILLS). */
    val pills: Map<String, Int>? = null,
    /** Short human-readable reason ("usual around 09:15"). */
    val reason: String
)

/**
 * Compact "likely next" flash shown once on app open. Suggestions render
 * as single-line chips (icon · name · pre-fill · ✓ · ✎ · 🚫) in a wrapping
 * flow layout, so the strip shows 6–8 suggestions in two short rows where
 * the old 148 dp cards showed ~2½. The ✓ applies a chip instantly, the ✎
 * opens the habit's normal input dialog with the payload pre-filled, the 🚫
 * opens the "don't suggest this anymore" popup. Closable via the ✕ corner.
 *
 * @param suggestions Ordered suggestions (highest score first)
 * @param visible Whether the flash is shown
 * @param iconOverrides `settings.habitIcons` — habit → icon name (for the chip icons)
 * @param aiIconRepo AI icon repository (null = AI icons disabled)
 * @param onConfirm One-tap increment for the suggestion
 * @param onEdit Open the habit's pre-filled input dialog
 * @param onExcludeRequest Open the "don't suggest this anymore" popup
 * @param onDismiss ✕ pressed — hide the flash
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionFlash(
    suggestions: List<HabitSuggestion>,
    visible: Boolean,
    iconOverrides: Map<String, String>,
    aiIconRepo: AiIconRepository?,
    onConfirm: (HabitSuggestion) -> Unit,
    onEdit: (HabitSuggestion) -> Unit,
    onExcludeRequest: (HabitSuggestion) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible && suggestions.isNotEmpty(),
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xFF232327), RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF444444), RoundedCornerShape(14.dp))
                .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)
        ) {
            // Header row with the close ✕ in the corner
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 2.dp)
            ) {
                Text(
                    text = "⚡ Likely next",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFFD700)
                )
                Spacer(modifier = Modifier.width(6.dp))
                // Why the top card is here ("usually done by 09:15") — the
                // chips themselves stay one line tall, so the reason lives
                // in the header instead of on every card.
                suggestions.firstOrNull()?.reason?.let { reason ->
                    Text(
                        text = "· ${suggestions.first().habitName} $reason",
                        fontSize = 10.sp,
                        color = Color(0xFF888888),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(Color(0xFF333338), CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 7.dp, vertical = 1.dp)
                ) {
                    Text(text = "✕", fontSize = 12.sp, color = Color(0xFFAAAAAA))
                }
            }
            Spacer(modifier = Modifier.padding(top = 5.dp))
            // Wrapping chip flow: as many chips as fit per row, extra rows
            // as needed. Chips are ~36 dp tall, so two rows ≈ one old card.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                suggestions.forEach { s ->
                    SuggestionChip(
                        suggestion = s,
                        iconOverrides = iconOverrides,
                        aiIconRepo = aiIconRepo,
                        onConfirm = { onConfirm(s) },
                        onEdit = { onEdit(s) },
                        onExcludeRequest = { onExcludeRequest(s) }
                    )
                }
            }
        }
    }
}

/**
 * Popup opened from a suggestion chip's 🚫 button: lets the user permanently
 * exclude that habit from future suggestions. The exclusion is always
 * reversible via Settings → Notifications → Increment suggestions.
 *
 * @param suggestion Habit being excluded (null = popup hidden)
 * @param onExclude Confirmed — persist the exclusion
 * @param onDismiss Cancel / hide
 */
@Composable
fun SuggestionExcludePopup(
    suggestion: HabitSuggestion?,
    onExclude: (HabitSuggestion) -> Unit,
    onDismiss: () -> Unit
) {
    if (suggestion == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = suggestion.habitName, fontSize = 16.sp) },
        text = {
            Text(
                text = "Stop suggesting “${suggestion.habitName}” in the ⚡ flash?\n\n" +
                    "You can undo this anytime in Settings → Notifications → " +
                    "Increment suggestions.",
                fontSize = 13.sp
            )
        },
        confirmButton = {
            TextButton(onClick = { onExclude(suggestion) }) {
                Text("🚫 Don't suggest anymore")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
