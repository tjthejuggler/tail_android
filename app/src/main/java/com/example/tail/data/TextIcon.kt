package com.example.tail.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

/** Prefix used in habitIcons values that reference a user-typed character icon (e.g. "text:🧘"). */
private const val TEXT_ICON_PREFIX = "text:"

/**
 * Returns true when the icon name references a user-typed letter/emoji icon
 * (i.e. it was stored by the "Text" section of the icon picker).
 */
fun isTextIconName(iconName: String?): Boolean =
    iconName != null && iconName.startsWith(TEXT_ICON_PREFIX)

/**
 * Extracts the displayed character from a habit icon name, or null if the
 * name does not reference a text icon.
 */
fun textIconCharOf(iconName: String?): String? =
    iconName?.takeIf { it.startsWith(TEXT_ICON_PREFIX) }
        ?.removePrefix(TEXT_ICON_PREFIX)
        ?.takeIf { it.isNotEmpty() }

/** Builds the habitIcons value that displays [character] as the habit icon. */
fun textIconNameOf(character: String): String = TEXT_ICON_PREFIX + character

/**
 * Renders [character] (a letter or emoji) to a square GREYSCALE bitmap for
 * use in the habit grid, matching the monochrome look of the built-in icons.
 *
 * Letters are drawn in white. Emoji glyphs render in colour, so a
 * saturation-0 colour matrix is applied to the paint to strip the colour and
 * keep only the luminance — the "greyscale version" of the emoji.
 */
fun renderTextIconBitmap(character: String, sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        // Saturation 0 → greyscale (letters are already white; emoji lose their colour)
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
    // Scale the text so even wide glyphs (emoji) fit inside the square
    var textSize = sizePx * 0.8f
    paint.textSize = textSize
    val width = paint.measureText(character)
    if (width > sizePx * 0.9f) {
        textSize *= (sizePx * 0.9f) / width
        paint.textSize = textSize
    }
    val metrics = paint.fontMetrics
    val baseline = sizePx / 2f + (metrics.descent - metrics.ascent) / 2f - metrics.descent
    canvas.drawText(character, sizePx / 2f, baseline, paint)
    return bitmap
}
