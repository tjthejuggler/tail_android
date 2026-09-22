package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared helpers + UI for overriding a movie habit's watch length.
 *
 * Movie minutes live in the "(N min)" annotation at the end of the logged
 * text entry — the minutes slot (`minutes:<habit>`) is re-synced from it by
 * `HabitViewModel.syncMovieMinutesSlot`. Overriding the length therefore
 * means editing that annotation, and every surface that confirms or edits a
 * movie entry can offer this wheel row with the file-derived length as the
 * default.
 */

/** Matches the trailing "(N min)" annotation of a movie entry. */
internal val MovieMinutesAnnotationRegex = Regex("""\((\d+)\s*min\)\s*$""")

/** The "(N min)" minutes of a movie entry text, or null when unannotated. */
internal fun parseMovieMinutesAnnotation(text: String): Int? =
    MovieMinutesAnnotationRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()

/**
 * Returns [text] with its trailing "(N min)" annotation replaced by (or
 * extended with) [minutes]. A non-positive [minutes] keeps the text as-is
 * (no annotation), matching how the bridge logs unknown lengths.
 */
internal fun withMovieMinutesAnnotation(text: String, minutes: Int): String {
    val base = MovieMinutesAnnotationRegex.replace(text.trim(), "").trimEnd()
    return if (minutes > 0) "$base ($minutes min)" else base
}

/**
 * "Length" row with the current watch length as a tappable label that
 * expands into the [DurationWheelPicker] — the same interaction as the
 * time pickers, reused across the ask flash, the notification center, the
 * timestamp editor and the text-input dialog.
 *
 * @param minutes Current minutes (drives both the label and the wheel).
 * @param onMinutesChange Called on every wheel change.
 * @param accent Accent color for the value label / wheel.
 */
@Composable
internal fun MovieMinutesWheelRow(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    accent: Color = Color(0xFFFFAA00)
) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Length",
            color = Color(0xFF888888),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
        val label = if (minutes >= 60) {
            "${minutes / 60} h ${minutes % 60} min"
        } else {
            "$minutes min"
        }
        TextButton(
            onClick = { expanded = !expanded },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 4.dp, end = 4.dp, top = 0.dp, bottom = 0.dp
            )
        ) {
            Text(
                text = if (expanded) "Done" else label,
                color = accent,
                fontSize = 13.sp
            )
        }
    }
    if (expanded) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111111), RoundedCornerShape(6.dp))
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            DurationWheelPicker(
                totalMinutes = minutes,
                onDurationChange = onMinutesChange,
                accent = accent
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    } else {
        Spacer(modifier = Modifier.height(4.dp))
    }
}
