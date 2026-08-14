package com.example.tail.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Transparent trampoline activity that:
 *  1. Checks whether the user has granted SYSTEM_ALERT_WINDOW ("draw over other apps").
 *  2. If granted → starts [FloatingBubbleService] and finishes immediately.
 *  3. If not granted → shows a simple Compose dialog explaining the permission with a
 *     button that opens the system "Display over other apps" settings screen.
 *
 * Launched from:
 *  - The Settings screen "Floating Bubble" section
 *  - The static launcher shortcut "Floating Bubble"
 */
class FloatingBubbleActivity : ComponentActivity() {

    companion object {
        const val ACTION_LAUNCH_BUBBLE = "com.example.tail.widget.LAUNCH_BUBBLE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (hasOverlayPermission()) {
            startBubbleService()
            finish()
        } else {
            showPermissionRequestUi()
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun startBubbleService() {
        val intent = Intent(this, FloatingBubbleService::class.java)
        startForegroundService(intent)
        Toast.makeText(this, "Floating bubble started", Toast.LENGTH_SHORT).show()
    }

    private fun showPermissionRequestUi() {
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.85f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Permission Needed",
                                fontSize = 20.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Tail needs the \"Display over other apps\" " +
                                    "permission to show the floating bubble on top of other apps.\n\n" +
                                    "Tap below, find Tail in the list, and enable the toggle.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = { openOverlaySettings() }) {
                                Text("Open Settings")
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { finish() },
                                colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
        finish()
    }
}
