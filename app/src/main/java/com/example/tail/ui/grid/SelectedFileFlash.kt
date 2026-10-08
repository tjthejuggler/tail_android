package com.example.tail.ui.grid

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Seconds before the selected-file flash auto-dismisses. */
private const val FILE_FLASH_SECONDS = 5

/**
 * Resolves a SAF content-URI string into a human-readable
 * (folderPath, fileName) pair for display in the edit-mode flash.
 *
 * - External-storage documents URIs (`.../document/primary:Docs/file.txt`)
 *   decode to the real relative path segments.
 * - Anything else falls back to DISPLAY_NAME query / last path segment,
 *   with the provider authority shown as the folder.
 */
internal fun resolveSafFilePath(context: Context, uriString: String): Pair<String, String> {
    val uri = Uri.parse(uriString)
    var displayName: String? = null
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) displayName = c.getString(idx)
            }
        }
    } catch (_: Exception) {
        // Provider unavailable — fall through to URI-derived names.
    }

    // Decode the SAF document id, e.g. "primary:Documents/journal.txt"
    val docId = try {
        uri.lastPathSegment?.substringAfter("document/", "") ?: ""
    } catch (_: Exception) {
        ""
    }
    val fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    val name = displayName?.takeIf { it.isNotBlank() } ?: docId.substringAfterLast('/')
        .takeIf { it.isNotBlank() } ?: fallbackName

    val folder = when {
        docId.contains(':') ->
            // "primary:Documents/sub" → "/storage/emulated/0/Documents/sub"
            "/storage/emulated/0/" + docId.substringAfter(':').substringBeforeLast('/')
                .trimEnd('/')
        docId.isNotBlank() -> "/" + docId.substringBeforeLast('/').trim('/')
        else -> uri.authority ?: ""
    }
    return folder to name
}

/**
 * The "✓ File selected" status label styled as a small subtle button —
 * tapping it flashes the hooked-up file's folder path and filename.
 */
@Composable
internal fun FileSelectedLabelButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = "✓ File selected ⤵",
        color = Color(0xFF88FF88),
        fontSize = 10.sp,
        modifier = modifier
            .background(Color(0xFF103810), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF3A7A3A), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/**
 * Bottom-flash style popup (same family as [RecordFlashPopup]) showing the
 * folder path + filename of the external text-log file hooked up to a habit.
 * Auto-dismisses after [FILE_FLASH_SECONDS]; tap to dismiss immediately.
 *
 * @param uriString SAF content-URI string stored in textInputFileUris
 * @param visible Whether the flash is currently showing
 * @param onDismiss Called on tap or timeout
 */
@Composable
internal fun SelectedFileFlash(
    uriString: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val (folder, fileName) = remember(uriString, visible) {
        if (visible) resolveSafFilePath(context, uriString)
        else "" to ""
    }

    LaunchedEffect(visible) {
        if (visible) {
            delay(FILE_FLASH_SECONDS * 1000L)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xFF0A1A0A), RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFF88FF88), RoundedCornerShape(10.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "📄 $fileName",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF88FF88)
            )
            Text(
                text = folder,
                fontSize = 10.sp,
                color = Color(0xFFAACCBB)
            )
        }
    }
}
