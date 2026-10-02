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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.WeightsRecordsRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Seconds before the PR flash auto-dismisses. */
const val WEIGHTS_PR_FLASH_SECONDS = 7

private val PR_FLASH_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/yy")

/**
 * Formats the flash's "old record" date + location story line. Returns null
 * when neither is known (record predates location tracking / no date).
 */
internal fun weightsPrOldRecordLabel(
    dateStr: String?,
    location: String?
): String? {
    val datePart = try {
        dateStr?.let { LocalDate.parse(it).format(PR_FLASH_DATE_FMT) }
    } catch (_: Exception) {
        null
    }
    return listOfNotNull(datePart?.let { "on $it" }, location?.let { "at $it" })
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ")
}

/**
 * A celebratory flash shown at the bottom of the screen (same style as
 * [HabitIncrementToast] / [MovieConfirmFlash]) when a logged weights set
 * sets a NEW ALL-TIME PERSONAL BEST for that exercise.
 *
 * Tells the user WHICH record fell (weight, reps, or both), the new value,
 * and the story of the old record: the date and location where it stood.
 * Auto-dismisses after [WEIGHTS_PR_FLASH_SECONDS]; tapping it dismisses
 * immediately.
 *
 * @param prCheck The PR evaluation result (which records fell + old snapshot)
 * @param exerciseName The exercise the record belongs to (headline)
 * @param unit Display unit for weights ("kg" or "lb")
 * @param visible Whether the flash is currently showing
 * @param onDismiss Called on tap or timeout
 * @param modifier Optional modifier for positioning (e.g. alignment in a Box)
 */
@Composable
fun WeightsPrFlash(
    prCheck: WeightsRecordsRepository.PrCheck,
    exerciseName: String,
    unit: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .background(Color(0xFF2A2408), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFFFD54F), RoundedCornerShape(12.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Headline: which exercise, and which record(s) fell
                val recordKind = when {
                    prCheck.weightPr && prCheck.repsPr -> "NEW ALL-TIME PB — WEIGHT & REPS!"
                    prCheck.weightPr -> "NEW ALL-TIME PB — WEIGHT!"
                    else -> "NEW ALL-TIME PB — REPS!"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🏆 $exerciseName",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD700)
                    )
                }
                Text(
                    text = recordKind,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFFD54F)
                )

                // New-value line(s) — only the record(s) that fell
                fun gramsLabel(grams: Int): String {
                    val tenths = com.example.tail.data.gramsToDisplayTenths(grams, unit)
                    return if (tenths % 10 == 0) "${tenths / 10} $unit"
                    else com.example.tail.data.formatWeightTenths(tenths) + " $unit"
                }
                if (prCheck.weightPr) {
                    Text(
                        text = "Heaviest ever: ${gramsLabel(prCheck.grams)} × ${prCheck.reps}",
                        fontSize = 12.sp,
                        color = Color(0xFFEEFFEE)
                    )
                }
                if (prCheck.repsPr) {
                    Text(
                        text = "Most reps ever: ${prCheck.reps} × ${gramsLabel(prCheck.grams)}",
                        fontSize = 12.sp,
                        color = Color(0xFFEEFFEE)
                    )
                }

                // Old-record story lines: date + location it stood at
                val old = prCheck.old
                if (prCheck.weightPr && !old.bestWeightDate.isNullOrEmpty()) {
                    Text(
                        text = "Previous best ${gramsLabel(old.bestWeightGrams)} — " +
                            (weightsPrOldRecordLabel(old.bestWeightDate, old.bestWeightLocation) ?: "all-time"),
                        fontSize = 11.sp,
                        color = Color(0xFFBBBB88)
                    )
                }
                if (prCheck.repsPr && !old.bestRepsDate.isNullOrEmpty()) {
                    Text(
                        text = "Previous best ${old.bestReps} reps — " +
                            (weightsPrOldRecordLabel(old.bestRepsDate, old.bestRepsLocation) ?: "all-time"),
                        fontSize = 11.sp,
                        color = Color(0xFFBBBB88)
                    )
                }
            }
        }
    }
}
