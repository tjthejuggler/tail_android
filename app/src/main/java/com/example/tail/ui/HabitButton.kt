package com.example.tail.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.Habit

// Shared style that strips the extra font padding Compose adds above/below text glyphs
private val tightTextStyle = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false)
)

/**
 * A single habit cell in the 8×10 grid.
 *
 * Layout:
 *   Top-left:     all-time high day count
 *   Top-right:    "+" badge if custom input mode
 *   Center:       icon image + habit name (truncated)
 *   Bottom-left:  current streak (positive) or antistreak (negative)
 *   Bottom-right: longest streak
 *
 * When [infoMode] is true, tapping shows info instead of incrementing.
 * When [isSelected] is true (info mode + this habit selected), a highlight border is shown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HabitButton(
    habit: Habit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    infoMode: Boolean = false,
    isSelected: Boolean = false,
    editMode: Boolean = false,
    /** True when this cell is the habit currently "in flight" waiting to be placed. */
    isMovePendingSource: Boolean = false,
    /** True when move-pending mode is active and this cell is a valid drop target. */
    isMovePendingTarget: Boolean = false,
    customIconOverrides: Map<String, String> = emptyMap()
) {
    val bgColor = getHabitColor(habit.name, habit.todayCount)
    val iconRes = getHabitIconRes(habit.name, customIconOverrides)
    val streakText = if (habit.currentStreak >= 0) "+${habit.currentStreak}" else "${habit.currentStreak}"

    val shape = RoundedCornerShape(6.dp)
    val borderMod = when {
        isMovePendingSource -> Modifier.border(2.dp, Color(0xFF44FFFF), shape)     // cyan border = "in flight"
        isSelected && editMode -> Modifier.border(2.dp, Color(0xFFFFAA00), shape)  // orange border when selected in edit mode
        isMovePendingTarget -> Modifier.border(1.dp, Color(0xFF44FFFF), shape)     // cyan border = valid drop target
        isSelected -> Modifier.border(2.dp, Color(0xFFFFD700), shape)              // gold border when selected in info mode
        infoMode   -> Modifier.border(1.dp, Color(0xFF88CCFF), shape)              // subtle blue border in info mode
        editMode   -> Modifier.border(1.dp, Color(0xFFFF8C00), shape)              // dim orange border in edit mode
        else       -> Modifier
    }
    // Dim the background slightly when this is a potential drop target (but not the source)
    val effectiveBgColor = when {
        isMovePendingSource -> bgColor.copy(alpha = 0.5f)
        isMovePendingTarget -> bgColor.copy(alpha = 0.7f)
        else -> bgColor
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(effectiveBgColor)
            .then(borderMod)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        // Top-left: all-time high day
        Text(
            text = habit.allTimeHighDay.toString(),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            style = tightTextStyle,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 1.dp, top = 0.dp)
        )

        // Top-right: move-pending indicator OR edit mode handle OR info mode indicator OR custom input badge
        if (isMovePendingSource) {
            Text(
                text = "↕",
                color = Color(0xFF44FFFF),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                style = tightTextStyle,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 1.dp, top = 0.dp)
            )
        } else if (editMode) {
            Text(
                text = "⠿",
                color = Color(0xFFFF8C00),
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                style = tightTextStyle,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 1.dp, top = 0.dp)
            )
        } else if (infoMode) {
            Text(
                text = "ℹ",
                color = Color(0xFF88CCFF),
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                style = tightTextStyle,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 1.dp, top = 0.dp)
            )
        } else if (habit.useCustomInput) {
            Text(
                text = "+",
                color = Color.Yellow,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                style = tightTextStyle,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 1.dp, top = 0.dp)
            )
        }

        // Center: icon only
        if (iconRes != null) {
            Image(
                painter = painterResource(id = iconRes),
                contentDescription = habit.name,
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.Center),
                colorFilter = ColorFilter.tint(Color.White)
            )
        }

        // Bottom-left: streak/antistreak
        Text(
            text = streakText,
            color = if (habit.currentStreak >= 0) Color(0xFF80FF80) else Color(0xFFFF8080),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            style = tightTextStyle,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 1.dp, bottom = 0.dp)
        )

        // Bottom-right: longest streak
        Text(
            text = habit.longestStreak.toString(),
            color = Color(0xFFADD8E6),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            style = tightTextStyle,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 1.dp, bottom = 0.dp)
        )
    }
}
