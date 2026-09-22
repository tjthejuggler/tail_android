package com.example.tail.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Converts a small subset of Markdown inline syntax to an [AnnotatedString].
 *
 * Supported tokens (processed in order, longest-match first):
 *   - `***text***`  → bold + italic
 *   - `**text**`    → bold
 *   - `*text*`      → italic
 *   - `_text_`      → italic
 *
 * Everything else is emitted as plain text.
 */
fun String.toMarkdownAnnotatedString(): AnnotatedString = buildAnnotatedString {
    val src = this@toMarkdownAnnotatedString
    var i = 0
    while (i < src.length) {
        when {
            // *** bold-italic ***
            src.startsWith("***", i) -> {
                val end = src.indexOf("***", i + 3)
                if (end != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic))
                    append(src.substring(i + 3, end))
                    pop()
                    i = end + 3
                } else {
                    append(src[i])
                    i++
                }
            }
            // ** bold **
            src.startsWith("**", i) -> {
                val end = src.indexOf("**", i + 2)
                if (end != -1) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(src.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else {
                    append(src[i])
                    i++
                }
            }
            // * italic * or _ italic _
            src[i] == '*' || src[i] == '_' -> {
                val marker = src[i]
                val end = src.indexOf(marker, i + 1)
                if (end != -1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(src.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append(src[i])
                    i++
                }
            }
            else -> {
                append(src[i])
                i++
            }
        }
    }
}
