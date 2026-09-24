package com.example.tail.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.environment.EnvironmentMetric
import com.example.tail.ui.viewmodel.HabitViewModel

/**
 * Edit-mode "Environment" habit-type section (Garmin-link style):
 * toggle a habit into the environment type and pick which metric
 * populates its squares. The daily stored value IS the metric, so the
 * habit's graph works natively next to any other habit.
 */
@Composable
internal fun EnvironmentLinkToggleSection(
    selectedHabitName: String,
    links: Map<String, String>,
    environmentEnabled: Boolean,
    onSetMetric: (String, String?) -> Unit
) {
    Spacer(modifier = Modifier.height(6.dp))

    val currentMetric = links[selectedHabitName]?.let { EnvironmentMetric.fromKey(it) }
    val isLinked = currentMetric != null
    var dropdownExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "🌍 Environment", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = when {
                    !environmentEnabled -> "Enable in Settings → Environment first"
                    isLinked -> "Metric: ${currentMetric!!.label} (${currentMetric.unit})"
                    else -> "Auto-populate from the day's location"
                },
                color = when {
                    !environmentEnabled -> Color(0xFFB06000)
                    isLinked -> Color(0xFF66BB6A)
                    else -> Color(0xFF888888)
                },
                fontSize = 10.sp
            )
        }
        Switch(
            checked = isLinked,
            onCheckedChange = { checked ->
                if (checked) {
                    dropdownExpanded = true
                } else {
                    onSetMetric(selectedHabitName, null)
                }
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF66BB6A),
                checkedTrackColor = Color(0xFF1B5E20),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }

    if (dropdownExpanded) {
        Spacer(modifier = Modifier.height(4.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .verticalScroll(rememberScrollState())
                .background(Color(0xFF161616))
                .padding(4.dp)
        ) {
            EnvironmentMetric.entries.forEach { metric ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            onSetMetric(selectedHabitName, metric.key)
                            dropdownExpanded = false
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(metric.label, fontSize = 11.sp, color = Color(0xFFDDDDDD))
                    Text(metric.unit, fontSize = 10.sp, color = Color(0xFF888888))
                }
            }
        }
    }
}
