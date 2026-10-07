package com.example.tail.ui.grid

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.example.tail.data.AppSettings

/**
 * One special permission that a settings-enabled feature needs but the
 * system has not granted (the expected state right after an app reinstall
 * or a backup restore onto a fresh install — special access grants are not
 * backed up or restored by Android).
 */
data class MissingPermissionItem(
    /** Short name shown on the row, e.g. "Notification access". */
    val label: String,
    /** Why it is needed, referencing the enabled feature(s). */
    val description: String,
    /** Fully-formed intent that opens the system grant screen. */
    val openIntent: Intent
)

/**
 * Computes which special permissions are missing but REQUIRED by features
 * the user has enabled in settings. Pure function of (context, settings) —
 * safe to call on the main thread (cheap Settings/AppOps queries only).
 *
 * Mapping (feature flag → permission):
 *  - statsOverlayEnabled / bubble full-screen & multi-timer trigger apps
 *      → draw-over-other-apps (SYSTEM_ALERT_WINDOW)
 *  - mediaApps (automatic listening-time tracking)
 *      → notification-listener access
 *  - widgetTriggerApps / mediaApps / chessReadinessEnabled (WidgetTriggerService)
 *      → usage access
 */
fun computeMissingPermissions(context: Context, settings: AppSettings): List<MissingPermissionItem> {
    val missing = mutableListOf<MissingPermissionItem>()

    val needsOverlay = settings.statsOverlayEnabled ||
        settings.bubbleFullScreenApps.isNotEmpty() ||
        settings.bubbleMultiTimerApps.isNotEmpty()
    if (needsOverlay && !Settings.canDrawOverlays(context)) {
        missing += MissingPermissionItem(
            label = "Draw over other apps",
            description = buildString {
                append("Needed by: ")
                val feats = mutableListOf<String>()
                if (settings.statsOverlayEnabled) feats += "stats overlay"
                if (settings.bubbleFullScreenApps.isNotEmpty()) feats += "bubble full-screen menu"
                if (settings.bubbleMultiTimerApps.isNotEmpty()) feats += "bubble multi-timer"
                append(feats.joinToString(", "))
            },
            openIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    val needsNotifListener = settings.mediaApps.isNotEmpty()
    if (needsNotifListener &&
        !com.example.tail.ipc.SpotifyDetector.isNotificationListenerEnabled(context)
    ) {
        missing += MissingPermissionItem(
            label = "Notification access",
            description = "Needed by: automatic media listening-time tracking",
            openIntent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    val needsUsageAccess = settings.widgetTriggerApps.isNotEmpty() ||
        settings.mediaApps.isNotEmpty() ||
        settings.chessReadinessEnabled
    if (needsUsageAccess && !com.example.tail.widget.WidgetTriggerService.hasUsageAccess(context)) {
        missing += MissingPermissionItem(
            label = "Usage access",
            description = buildString {
                append("Needed by: ")
                val feats = mutableListOf<String>()
                if (settings.widgetTriggerApps.isNotEmpty()) feats += "widget triggers"
                if (settings.mediaApps.isNotEmpty()) feats += "media tracking"
                if (settings.chessReadinessEnabled) feats += "chess readiness"
                append(feats.joinToString(", "))
            },
            openIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    return missing
}

/**
 * Dismissible banner shown at the top of the habit grid whenever features
 * the user enabled in settings are missing their special permission grants
 * — the expected situation after an app reinstall or backup restore, since
 * Android backs up neither overlay, notification-listener nor usage-access
 * grants. Each row has a button that opens the matching system settings
 * screen.
 *
 * Dismissing hides the banner for the current session only; it reappears on
 * the next app start if the grants are still missing (or immediately when
 * the user returns from the system settings screen and the state is
 * recomputed on lifecycle resume).
 */
@Composable
fun PermissionRestoreBanner(settings: AppSettings) {
    val context = LocalContext.current
    var dismissed by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(computeMissingPermissions(context, settings)) }

    // Recompute whenever the screen comes back to the foreground — the user
    // may just have granted something in the system settings screen.
    LifecycleStartEffect(settings) {
        missing = computeMissingPermissions(context, settings)
        onStopOrDispose { }
    }

    if (dismissed || missing.isEmpty()) return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Re-enable permissions",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 15.sp
                )
                TextButton(onClick = { dismissed = true }) {
                    Text("Hide", fontSize = 13.sp)
                }
            }
            Text(
                text = "Your settings were restored, but these system grants don't survive a reinstall:",
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                fontSize = 12.sp
            )
            missing.forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        Text(
                            text = item.label,
                            color = Color(0xFFFFCCCC),
                            fontSize = 14.sp
                        )
                        Text(
                            text = item.description,
                            color = Color(0xFFE0B0B0),
                            fontSize = 11.sp
                        )
                    }
                    TextButton(onClick = {
                        try {
                            context.startActivity(item.openIntent)
                        } catch (_: Exception) {
                        }
                    }) {
                        Text("Open settings", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
