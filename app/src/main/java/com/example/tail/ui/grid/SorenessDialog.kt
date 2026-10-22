package com.example.tail.ui.grid

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.SorenessDataRepository
import com.example.tail.data.SorenessDay
import com.example.tail.ui.viewmodel.HabitViewModel
import com.example.tail.ui.viewmodel.saveSorenessDay
import java.time.LocalDate

/** Region display labels (machine key → label). */
private val REGION_LABELS = mapOf(
    "legs" to "Legs",
    "back" to "Back",
    "shoulders" to "Shoulders",
    "arms" to "Arms",
    "other" to "Other"
)

/** 0–3 scale labels for the header hint. */
private const val SCALE_HINT = "0 none · 1 minor niggle · 2 noticeable · 3 pain affecting movement"

/**
 * Soreness nightly dialog: 0–3 rating per body region + a paired
 * "trained today" boolean, exported to Soreness_daily.json. "Nothing sore"
 * is a one-tap save of an explicit all-zero row (a real zero is signal).
 */
@Composable
fun SorenessDialog(
    date: LocalDate,
    existing: SorenessDay?,
    viewModel: HabitViewModel,
    onDismiss: () -> Unit
) {
    var trained by remember { mutableStateOf(existing?.trained ?: false) }
    var ratings by remember {
        mutableStateOf(
            SorenessDataRepository.REGIONS.associateWith { r -> existing?.regions?.get(r) ?: 0 }
        )
    }

    val save: (Map<String, Int>, Boolean) -> Unit = { regs, tr ->
        viewModel.saveSorenessDay(date, tr, regs)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Soreness — $date", fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(SCALE_HINT, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))

                SorenessDataRepository.REGIONS.forEach { region ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            REGION_LABELS[region] ?: region,
                            fontSize = 13.sp,
                            modifier = Modifier.width(88.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (0..3).forEach { v ->
                                FilterChip(
                                    selected = (ratings[region] ?: 0) == v,
                                    onClick = {
                                        ratings = ratings + (region to v)
                                    },
                                    label = { Text("$v", fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Trained today", fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = trained, onCheckedChange = { trained = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save(ratings.toMap(), trained) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = {
                // One-tap explicit zero row: "nothing sore" IS a data point.
                save(SorenessDataRepository.REGIONS.associateWith { 0 }, trained)
            }) { Text("Nothing sore") }
        }
    )
}
