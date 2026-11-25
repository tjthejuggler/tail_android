package com.example.tail.ui.grid

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.R
import com.example.tail.data.AiIconRepository
import com.example.tail.data.appIconMonochromeOf
import com.example.tail.data.appPackageNameOf
import com.example.tail.data.loadAppIconBitmap
import com.example.tail.data.renderTextIconBitmap
import com.example.tail.data.textIconCharOf
import com.example.tail.ui.common.getHabitIconRes

/**
 * Resolves and draws the icon of [habitName] exactly the way the grid tile
 * does (see [HabitButton]): installed-app icon → AI-generated icon →
 * user-typed letter/emoji → drawable resource (tinted). Habits with no
 * icon at all render nothing.
 *
 * @param iconOverrides `settings.habitIcons` — habit → icon name
 * @param aiIconRepo Repository for file-based AI icons (null = AI icons off)
 * @param size Square icon size
 * @param tint Optional tint for drawable icons (the grid uses white)
 */
@Composable
fun HabitIcon(
    habitName: String,
    iconOverrides: Map<String, String>,
    modifier: Modifier = Modifier,
    aiIconRepo: AiIconRepository? = null,
    size: androidx.compose.ui.unit.Dp = 18.dp,
    tint: Color? = Color.White
) {
    val override = iconOverrides[habitName]
    // Check if this habit uses an AI-generated icon (id starts with "ai_")
    val aiIconId = override?.takeIf { it.startsWith("ai_") }
    val aiIconBitmap: Bitmap? = remember(aiIconId) {
        if (aiIconId != null && aiIconRepo != null) aiIconRepo.loadBitmap(aiIconId) else null
    }
    // Installed-app icon ("app:<pkg>[#mono]")
    val pkg = appPackageNameOf(override)
    val mono = appIconMonochromeOf(override)
    val context = LocalContext.current
    val appIconBitmap: Bitmap? = remember(pkg, mono) {
        if (pkg == null) null else loadAppIconBitmap(context, pkg, mono)
    }
    // User-typed letter/emoji icon ("text:<char>")
    val textChar = textIconCharOf(override)
    val textIconBitmap: Bitmap? = remember(textChar) {
        if (textChar != null) renderTextIconBitmap(textChar, 96) else null
    }
    val iconRes = getHabitIconRes(habitName, iconOverrides)

    val sizeMod = modifier.size(size).clip(RoundedCornerShape(3.dp))
    when {
        appIconBitmap != null -> Image(
            bitmap = appIconBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = sizeMod
        )
        aiIconBitmap != null -> Image(
            bitmap = aiIconBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = sizeMod
        )
        textIconBitmap != null -> Image(
            bitmap = textIconBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = sizeMod
        )
        iconRes != null -> Image(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = sizeMod,
            colorFilter = tint?.let { ColorFilter.tint(it) }
        )
    }
}

/**
 * One compact suggestion chip: [icon] habit name [✓ +n] [✎] — a single
 * row, ~36 dp tall, so the strip fits far more suggestions on screen than
 * the old 148 dp-wide three-line cards did.
 *
 * Layout (left → right):
 *  · habit icon (same resolution as the grid tile)
 *  · habit name, then the pre-fill summary ("+2 Reading", "“text”", pills),
 *    on one line, each ellipsised when long
 *  · green ✓ button (not shown for WEIGHTS — opens the dialog instead)
 *  · grey ✎ button for the kinds that have a dialog to pre-fill
 *  · tiny 🚫 to open the "don't suggest anymore" popup
 *
 * @param onConfirm One-tap increment
 * @param onEdit Open the habit's pre-filled input dialog
 * @param onExcludeRequest Open the exclusion popup
 */
@Composable
fun SuggestionChip(
    suggestion: HabitSuggestion,
    iconOverrides: Map<String, String>,
    aiIconRepo: AiIconRepository?,
    onConfirm: () -> Unit,
    onEdit: () -> Unit,
    onExcludeRequest: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .widthIn(min = 150.dp, max = 230.dp)
            .height(36.dp)
            .background(Color(0xFF2A2A2E), RoundedCornerShape(9.dp))
            .border(1.dp, Color(0xFF3D3D44), RoundedCornerShape(9.dp))
            .padding(start = 8.dp, end = 4.dp)
    ) {
        HabitIcon(
            habitName = suggestion.habitName,
            iconOverrides = iconOverrides,
            aiIconRepo = aiIconRepo,
            size = 18.dp
        )
        Spacer(modifier = Modifier.width(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = suggestion.habitName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            // Pre-fill summary — what the ✓ will actually log
            val summary = when (suggestion.kind) {
                SuggestionKind.WEIGHTS -> "opens weight logging"
                SuggestionKind.TEXT -> "“${suggestion.text?.take(40)?.replace('\n', '·')}”"
                SuggestionKind.SUBTYPE -> "+${suggestion.amount} ${suggestion.subtype}"
                SuggestionKind.PILLS -> suggestion.pills.orEmpty().entries.joinToString(" · ") { (p, n) ->
                    if (n > 1) "$p ×$n" else p
                }
                SuggestionKind.CUSTOM_AMOUNT, SuggestionKind.PLAIN -> "+${suggestion.amount}"
            }
            if (summary.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = summary,
                    fontSize = 11.sp,
                    color = Color(0xFFBBBBBB),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        if (suggestion.kind != SuggestionKind.WEIGHTS) {
            ChipButton(
                text = if (suggestion.kind == SuggestionKind.CUSTOM_AMOUNT ||
                    suggestion.kind == SuggestionKind.PLAIN
                ) "✓ +${suggestion.amount}" else "✓",
                background = Color(0xFF1B5E20),
                contentColor = Color.White,
                onClick = onConfirm
            )
        }
        if (suggestion.kind != SuggestionKind.PLAIN) {
            Spacer(modifier = Modifier.width(4.dp))
            ChipButton(
                text = "✎",
                background = Color(0xFF333338),
                contentColor = Color(0xFFCCCCCC),
                onClick = onEdit
            )
        }
        Spacer(modifier = Modifier.width(2.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onExcludeRequest)
                .padding(horizontal = 4.dp, vertical = 3.dp)
        ) {
            Text(text = "🚫", fontSize = 9.sp, maxLines = 1)
        }
    }
}

/** Small pill-shaped text button used inside a chip. */
@Composable
private fun ChipButton(
    text: String,
    background: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1
        )
    }
}
