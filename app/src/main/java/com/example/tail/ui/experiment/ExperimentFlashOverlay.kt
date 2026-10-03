package com.example.tail.ui.experiment

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.ExperimentStatus
import kotlinx.coroutines.delay

/**
 * Full-screen "flash" reminder shown on app open while a TailCue experiment is
 * running: a brief color pulse, then the experiment card stays for a few seconds
 * (or until dismissed). Auto-hides so it never blocks normal habit logging.
 */
@Composable
fun ExperimentFlashOverlay(status: ExperimentStatus?, onDismiss: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    var show by remember { mutableStateOf(false) }
    val pulse by animateFloatAsState(
        targetValue = if (visible) 1f else 0.35f,
        animationSpec = tween(700), label = "pulse"
    )

    LaunchedEffect(status?.expId) {
        if (status != null) {
            visible = true
            show = true
            delay(6500)
            show = false
            delay(400)
            visible = false
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = show && status != null,
        enter = fadeIn(tween(250)) + scaleIn(initialScale = 0.92f, animationSpec = tween(250)),
        exit = fadeOut(tween(350)) + scaleOut(targetScale = 0.95f, animationSpec = tween(350))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1220).copy(alpha = 0.94f))
                .alpha(pulse)
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("⚗️", fontSize = 56.sp)
            Spacer(Modifier.height(16.dp))
            Text(
                "YOU ARE IN AN EXPERIMENT",
                color = Color(0xFF7DE2A8),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(20.dp))
            Text(
                status?.title ?: "",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            val s = status ?: return@Column
            Text(
                "Day ${s.daysIn} · ${s.daysLeft} day${if (s.daysLeft == 1) "" else "s"} left (ends ${s.endsAt})",
                color = Color(0xFF9AB0C8),
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(36.dp))
            Button(
                onClick = { show = false; onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D7A4C)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text("Got it — carry on", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
