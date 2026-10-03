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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.RecordFlashEvent
import com.example.tail.data.RecordTier

/** Seconds before the record flash auto-dismisses. */
const val RECORD_FLASH_SECONDS = 7

/** Human label for a broken record tier. */
private fun tierLabel(tier: RecordTier): String = when (tier) {
    RecordTier.ALL_TIME -> "ALL-TIME"
    RecordTier.ROLLING_30D -> "30-DAY"
    RecordTier.ROLLING_365D -> "365-DAY"
}

/**
 * Celebratory popup shown on the grid screen when a habit with the
 * "New record" notification enabled logs an amount input that pushes a
 * channel (a subtype option, a weights exercise, or the habit itself)
 * past one of its standing DAY-TOTAL records: the all-time best day, the
 * best rolling 30-day sum, or the best rolling 365-day sum. A single
 * popup lists EVERY tier the input broke.
 *
 * Same bottom-flash style as [WeightsPrFlash] in a purple/gold scheme.
 * Auto-dismiss is handled by the host via [RECORD_FLASH_SECONDS]; tapping
 * it dismisses immediately.
 *
 * @param event The record event (which channel fell + the tier beats)
 * @param visible Whether the flash is currently showing
 * @param onDismiss Called on tap or timeout
 * @param modifier Optional modifier for positioning (e.g. alignment in a Box)
 */
@Composable
fun RecordFlashPopup(
    event: RecordFlashEvent,
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
                .background(Color(0xFF1A0A2A), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFB388FF), RoundedCornerShape(12.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "🏆 NEW RECORD!",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFFFD700)
                )
                Text(
                    text = if (event.isSubtype) "${event.habitName} — ${event.channelLabel}"
                    else event.channelLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE1BEE7)
                )
                for (beat in event.beats) {
                    Text(
                        text = "${tierLabel(beat.tier)}: ${beat.newValue}" +
                            if (beat.oldValue > 0) " (previous: ${beat.oldValue})" else " — first ever!",
                        fontSize = 12.sp,
                        color = Color(0xFFEEFFEE)
                    )
                }
            }
        }
    }
}
