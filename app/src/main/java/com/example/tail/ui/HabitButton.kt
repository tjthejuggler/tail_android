package com.example.tail.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.Habit

/**
 * A single habit cell in the 8×10 grid.
 *
 * Layout:
 *   Top-left:     all-time high day count
 *   Top-right:    "+" badge if custom input mode
 *   Center:       icon image + habit name (truncated)
 *   Bottom-left:  current streak (positive) or antistreak (negative)
 *   Bottom-right: longest streak
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HabitButton(
    habit: Habit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = getHabitColor(habit.name, habit.todayCount)
    val iconRes = getHabitIconRes(habit.name)
    val streakText = if (habit.currentStreak >= 0) "+${habit.currentStreak}" else "${habit.currentStreak}"

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(3.dp)
    ) {
        // Top-left: all-time high day
        Text(
            text = habit.allTimeHighDay.toString(),
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart)
        )

        // Top-right: custom input badge
        if (habit.useCustomInput) {
            Text(
                text = "+",
                color = Color.Yellow,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        // Center: icon + name
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 10.dp, bottom = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (iconRes != null) {
                    Image(
                        painter = painterResource(id = iconRes),
                        contentDescription = habit.name,
                        modifier = Modifier.size(18.dp),
                        colorFilter = ColorFilter.tint(Color.White)
                    )
                }
                Text(
                    text = habit.name,
                    color = Color.White,
                    fontSize = 7.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 8.sp
                )
            }
        }

        // Bottom-left: streak/antistreak
        Text(
            text = streakText,
            color = if (habit.currentStreak >= 0) Color(0xFF80FF80) else Color(0xFFFF8080),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomStart)
        )

        // Bottom-right: longest streak
        Text(
            text = habit.longestStreak.toString(),
            color = Color(0xFFADD8E6),
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}
