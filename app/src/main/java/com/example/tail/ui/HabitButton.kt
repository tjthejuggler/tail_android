package com.example.tail.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.AiIconRepository
import com.example.tail.data.GarminType
import com.example.tail.data.Habit
import com.example.tail.data.appLinkPackageName
import com.example.tail.data.appPackageNameOf

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
 * When [isSelected] is true, a highlight border is shown.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HabitButton(
    habit: Habit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    editMode: Boolean = false,
    /** True when this cell is the habit currently "in flight" waiting to be placed. */
    isMovePendingSource: Boolean = false,
    /** True when move-pending mode is active and this cell is a valid drop target. */
    isMovePendingTarget: Boolean = false,
    customIconOverrides: Map<String, String> = emptyMap(),
    graphMode: Boolean = false,
    isGraphSelected: Boolean = false,
    /** True when this habit is disabled (red ✕ overlay in top-left corner). */
    isDisabled: Boolean = false,
    /** Optional AI icon repository for loading file-based AI icons. */
    aiIconRepo: AiIconRepository? = null,
    /** Map of habit name → GarminType.name for Garmin-linked habits. Used to format values (e.g. metres → km). */
    garminHabitLinks: Map<String, String> = emptyMap(),
    /** True when this habit has one or more associated apps (long-press launches them). */
    hasAppAssociation: Boolean = false
) {
    val habitStyle = getHabitStyle(habit.todayCount)
    // Animate color transitions smoothly to prevent flickering
    val bgColor by animateColorAsState(
        targetValue = habitStyle.background,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "habitBackgroundColor"
    )
    
    // Click animation state
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "habitButtonScale"
    )
    val iconRes = getHabitIconRes(habit.name, customIconOverrides)
    // Check if this habit uses an AI-generated icon (id starts with "ai_")
    val aiIconId = customIconOverrides[habit.name]?.takeIf { it.startsWith("ai_") }
    val aiIconBitmap: Bitmap? = remember(aiIconId) {
        if (aiIconId != null && aiIconRepo != null) aiIconRepo.loadBitmap(aiIconId) else null
    }
    // Check if this habit uses an installed-app icon (name starts with "app:")
    val appIconPackageName = appPackageNameOf(customIconOverrides[habit.name])
    val appContext = LocalContext.current
    val appIconBitmap: Bitmap? = remember(appIconPackageName) {
        if (appIconPackageName == null) null
        else try {
            drawableToBitmap(appContext.packageManager.getApplicationIcon(appIconPackageName))
        } catch (e: Exception) {
            null
        }
    }
    val streakText = if (habit.currentStreak >= 0) "+${habit.currentStreak}" else "${habit.currentStreak}"
    
    // Format allTimeHighDay for Garmin distance habits (metres → km whole number)
    val highDayText = garminHabitLinks[habit.name]?.let { GarminType.fromKey(it) }?.formatDisplayValue(habit.allTimeHighDay)
        ?: habit.allTimeHighDay.toString()

    val shape = RoundedCornerShape(6.dp)

    // Color-tier border(s) — drawn BEHIND content so they never cover text/icons.
    //  Phase 3 (count 7–12):  single coloured border.
    //  Phase 4 (count 13+):   double border — outer colour | thin black separator | inner colour.
    //  We use drawWithContent (borders first, then drawContent) instead of Modifier.border()
    //  because .border() draws ON TOP of content and chained .border() calls all render at the
    //  same inset (overlapping).  Manual drawRoundRect with Stroke lets us inset each ring
    //  independently and place them behind the children.
    val tierBorderMod = when {
        habitStyle.outerBorderColor != null -> {
            val outer = habitStyle.outerBorderColor!!
            val inner = habitStyle.innerBorderColor!!
            Modifier.drawWithContent {
                val outerW = 2.dp.toPx()
                val gapW = 1.dp.toPx()
                val innerW = 2.dp.toPx()
                val cornerPx = 6.dp.toPx()

                // Outer ring (outermost edge)
                drawRoundRect(
                    color = outer,
                    topLeft = Offset(outerW / 2, outerW / 2),
                    size = Size(size.width - outerW, size.height - outerW),
                    cornerRadius = CornerRadius((cornerPx - outerW / 2).coerceAtLeast(0f)),
                    style = Stroke(width = outerW)
                )
                // Thin black separator
                drawRoundRect(
                    color = Color.Black,
                    topLeft = Offset(outerW + gapW / 2, outerW + gapW / 2),
                    size = Size(size.width - 2 * outerW - gapW, size.height - 2 * outerW - gapW),
                    cornerRadius = CornerRadius((cornerPx - outerW - gapW / 2).coerceAtLeast(0f)),
                    style = Stroke(width = gapW)
                )
                // Inner ring
                drawRoundRect(
                    color = inner,
                    topLeft = Offset(outerW + gapW + innerW / 2, outerW + gapW + innerW / 2),
                    size = Size(
                        size.width - 2 * outerW - 2 * gapW - innerW,
                        size.height - 2 * outerW - 2 * gapW - innerW
                    ),
                    cornerRadius = CornerRadius(
                        (cornerPx - outerW - gapW - innerW / 2).coerceAtLeast(0f)
                    ),
                    style = Stroke(width = innerW)
                )
                // Content drawn on top of all borders
                drawContent()
            }
        }
        habitStyle.borderColor != null -> {
            val border = habitStyle.borderColor
            Modifier.drawWithContent {
                val w = 2.dp.toPx()
                val cornerPx = 6.dp.toPx()
                drawRoundRect(
                    color = border,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius((cornerPx - w / 2).coerceAtLeast(0f)),
                    style = Stroke(width = w)
                )
                drawContent()
            }
        }
        else -> Modifier
    }

    // Mode-specific borders — these take visual priority over the tier border
    val modeBorderMod = when {
        isMovePendingSource -> Modifier.border(2.dp, Color(0xFF44FFFF), shape)     // cyan border = "in flight"
        isGraphSelected -> Modifier.border(2.dp, Color(0xFF66DD66), shape)         // green border when selected for graph
        isSelected && editMode -> Modifier.border(2.dp, Color(0xFFFFAA00), shape)  // orange border when selected in edit mode
        isMovePendingTarget -> Modifier.border(1.dp, Color(0xFF44FFFF), shape)     // cyan border = valid drop target
        isSelected -> Modifier.border(2.dp, Color(0xFFFFD700), shape)              // gold border when selected
        graphMode  -> Modifier.border(1.dp, Color(0xFF1A4A1A), shape)              // dim green border in graph mode
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
            .graphicsLayer { this.scaleX = scale; this.scaleY = scale }
            .clip(shape)
            .background(effectiveBgColor)
            .then(tierBorderMod)
            .then(modeBorderMod)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                interactionSource = remember { MutableInteractionSource() }.also { source ->
                    LaunchedEffect(source) {
                        source.interactions.collect { interaction ->
                            when (interaction) {
                                is PressInteraction.Press -> isPressed = true
                                is PressInteraction.Release -> isPressed = false
                                is PressInteraction.Cancel -> isPressed = false
                            }
                        }
                    }
                }
            )
    ) {
        // Top-left: all-time high day (formatted for Garmin distance habits)
        Text(
            text = highDayText,
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            style = tightTextStyle,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 1.dp, top = 0.dp)
        )

        // Disabled overlay: red ✕ in top-right corner
        if (isDisabled) {
            Text(
                text = "✕",
                color = Color(0xFFFF2222),
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                style = tightTextStyle,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 0.dp, top = 0.dp)
            )
        }

        // Top-right: move-pending indicator OR graph mode indicator OR edit mode handle OR custom input badge
        if (isGraphSelected) {
            Text(
                text = "📊",
                color = Color(0xFF66DD66),
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                style = tightTextStyle,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 1.dp, top = 0.dp)
            )
        } else if (graphMode) {
            Text(
                text = "○",
                color = Color(0xFF1A4A1A),
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                style = tightTextStyle,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 1.dp, top = 0.dp)
            )
        } else if (isMovePendingSource) {
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
        } else if (hasAppAssociation) {
            // Blue arrow indicates long-press launches an associated app
            Text(
                text = "↗",
                color = Color(0xFF66CCFF),
                fontSize = 10.sp,
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

        // Center: icon — app icon takes priority over AI icon, then drawable resource
        if (appIconBitmap != null) {
            Image(
                bitmap = appIconBitmap.asImageBitmap(),
                contentDescription = habit.name,
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.Center)
            )
        } else if (aiIconBitmap != null) {
            Image(
                bitmap = aiIconBitmap.asImageBitmap(),
                contentDescription = habit.name,
                modifier = Modifier
                    .size(20.dp)
                    .align(Alignment.Center)
            )
        } else if (iconRes != null) {
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

/**
 * Converts an Android [Drawable] to a [Bitmap].
 * If the drawable is already a [BitmapDrawable], its bitmap is returned directly.
 * Otherwise a new bitmap is created at the drawable's intrinsic size.
 */
private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable) return drawable.bitmap
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 1
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 1
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

/**
 * A grid cell that acts as an app launcher link (not an incrementable habit).
 *
 * Visually distinct from [HabitButton]: dark blue-teal background, the app's
 * own icon shown at a larger size (no tint), and a small "↗" indicator in the
 * top-right corner to signal that tapping opens an external app.
 *
 * In edit mode, an orange selection border is shown and the app label appears
 * at the bottom.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppLinkButton(
    appLinkKey: String,
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    editMode: Boolean = false,
    isSelected: Boolean = false,
    isMovePendingSource: Boolean = false,
    isMovePendingTarget: Boolean = false
) {
    val context = LocalContext.current
    val packageName = appLinkPackageName(appLinkKey) ?: return

    // Load the app icon bitmap once (cached by package name)
    val iconBitmap: Bitmap? = remember(packageName) {
        try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            drawableToBitmap(drawable)
        } catch (e: Exception) {
            null
        }
    }

    // Click animation
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "appLinkScale"
    )

    val shape = RoundedCornerShape(6.dp)

    // Mode-specific borders
    val modeBorderMod = when {
        isMovePendingSource -> Modifier.border(2.dp, Color(0xFF44FFFF), shape)
        isSelected && editMode -> Modifier.border(2.dp, Color(0xFFFFAA00), shape)
        isMovePendingTarget -> Modifier.border(1.dp, Color(0xFF44FFFF), shape)
        editMode -> Modifier.border(1.dp, Color(0xFFFF8C00), shape)
        else -> Modifier
    }

    // Distinctive background: barely-visible dark blue, close to black
    val bgColor = if (isMovePendingSource) Color(0xFF0A0E12).copy(alpha = 0.5f)
                  else if (isMovePendingTarget) Color(0xFF0A0E12).copy(alpha = 0.7f)
                  else Color(0xFF0A0E12)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .graphicsLayer { this.scaleX = scale; this.scaleY = scale }
            .clip(shape)
            .background(bgColor)
            .then(modeBorderMod)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                interactionSource = remember { MutableInteractionSource() }.also { source ->
                    LaunchedEffect(source) {
                        source.interactions.collect { interaction ->
                            when (interaction) {
                                is PressInteraction.Press -> isPressed = true
                                is PressInteraction.Release -> isPressed = false
                                is PressInteraction.Cancel -> isPressed = false
                            }
                        }
                    }
                }
            )
    ) {
        // Top-right: launch indicator "↗" (signals this opens an external app)
        Text(
            text = "↗",
            color = Color(0xFF66CCFF),
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            style = tightTextStyle,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 1.dp, top = 0.dp)
        )

        // Center: app icon (larger than habit icons, no tint — show the real icon)
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap.asImageBitmap(),
                contentDescription = label,
                modifier = Modifier
                    .size(28.dp)
                    .align(Alignment.Center)
            )
        }

        // Bottom: app label (only in edit mode to avoid clutter)
        if (editMode) {
            Text(
                text = label.take(10),
                color = Color(0xFF88CCFF),
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                style = tightTextStyle,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 1.dp)
            )
        }
    }
}
