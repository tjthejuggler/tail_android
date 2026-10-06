package com.example.tail.ui.grid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    /** Subtyped habit — ✓ increments the default subtype (✎ opens the subtype dialog). */
    SUBTYPE,
    /** Weights habit — no one-tap increment; ✎ opens the weights logging dialog. */
    WEIGHTS
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
    /** Short human-readable reason ("usual around 09:15"). */
    val reason: String
)

/**
 * Scrollable "likely next" flash strip shown once on app open. Each card
 * carries a fully pre-filled increment (amount / text / subtype); the ✓
 * applies it instantly, and the ✎ opens the habit's normal input dialog
 * with the payload already typed in / selected. Closable via the ✕ in the
 * corner.
 *
 * @param suggestions Ordered suggestions (highest score first)
 * @param visible Whether the flash is shown
 * @param onConfirm One-tap increment for the suggestion
 * @param onEdit Open the habit's pre-filled input dialog
 * @param onDismiss ✕ pressed — hide the flash
 */
@Composable
fun SuggestionFlash(
    suggestions: List<HabitSuggestion>,
    visible: Boolean,
    onConfirm: (HabitSuggestion) -> Unit,
    onEdit: (HabitSuggestion) -> Unit,
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
                .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 10.dp)
        ) {
            // Header row with the close ✕ in the corner
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 4.dp)
            ) {
                Text(
                    text = "⚡ Likely next",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFFD700)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "· one-tap increment",
                    fontSize = 11.sp,
                    color = Color(0xFF888888)
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .background(Color(0xFF333338), CircleShape)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(text = "✕", fontSize = 13.sp, color = Color(0xFFAAAAAA))
                }
            }
            Spacer(modifier = Modifier.padding(top = 6.dp))
            // Horizontally scrollable suggestion cards
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                suggestions.forEach { s ->
                    SuggestionCard(
                        suggestion = s,
                        onConfirm = { onConfirm(s) },
                        onEdit = { onEdit(s) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: HabitSuggestion,
    onConfirm: () -> Unit,
    onEdit: () -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .width(148.dp)
            .background(Color(0xFF2A2A2E), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF3D3D44), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text(
            text = suggestion.habitName,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // Pre-fill summary — what the ✓ will actually log
        Text(
            text = when (suggestion.kind) {
                SuggestionKind.WEIGHTS -> "opens weight logging"
                SuggestionKind.TEXT -> "“${suggestion.text?.take(40)}”"
                SuggestionKind.SUBTYPE -> "+1 ${suggestion.subtype}"
                SuggestionKind.CUSTOM_AMOUNT -> "+${suggestion.amount}"
                SuggestionKind.PLAIN -> "+${suggestion.amount}"
            },
            fontSize = 11.sp,
            color = Color(0xFFBBBBBB),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = suggestion.reason,
            fontSize = 10.sp,
            color = Color(0xFF777777),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (suggestion.kind != SuggestionKind.WEIGHTS) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF1B5E20), RoundedCornerShape(6.dp))
                        .clickable(onClick = onConfirm)
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (suggestion.kind == SuggestionKind.CUSTOM_AMOUNT ||
                            suggestion.kind == SuggestionKind.PLAIN
                        ) "✓ +${suggestion.amount}" else "✓",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }
            if (suggestion.kind == SuggestionKind.TEXT ||
                suggestion.kind == SuggestionKind.SUBTYPE ||
                suggestion.kind == SuggestionKind.WEIGHTS ||
                suggestion.kind == SuggestionKind.CUSTOM_AMOUNT
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFF333338), RoundedCornerShape(6.dp))
                        .clickable(onClick = onEdit)
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "✎ edit",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFCCCCCC),
                        maxLines = 1
                    )
                }
            }
        }
    }
}
