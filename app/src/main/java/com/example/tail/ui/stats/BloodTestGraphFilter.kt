package com.example.tail.ui.stats

// Blood-test graph item filter: searchable multi-select popup listing every
// item ever measured for the habit, plus the "Items tested" daily-count
// series (default). Selection persists via graphMetricSelection.

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.bloodtest.BLOOD_METRIC_COUNT
import com.example.tail.data.bloodtest.bloodMetricKey
import com.example.tail.data.bloodtest.bloodMetricLabel
import com.example.tail.ui.viewmodel.HabitViewModel
import com.example.tail.ui.viewmodel.bloodTestItemNames
import com.example.tail.ui.viewmodel.getSelectedMetrics
import com.example.tail.ui.viewmodel.setBloodTestGraphSelection

/**
 * @param habitName the blood-test habit
 * @param onDismiss closes the popup (with or without applying)
 */
@Composable
fun BloodTestItemFilterPopup(
    habitName: String,
    viewModel: HabitViewModel,
    onDismiss: () -> Unit
) {
    // All items ever measured (sync file read; blood-test stores are small)
    val allItems = remember(habitName) { viewModel.bloodTestItemNames(habitName) }
    val selected = remember { mutableStateOf(viewModel.getSelectedMetrics(habitName)) }
    var query by remember { mutableStateOf("") }

    fun toggle(key: String) {
        selected.value =
            if (key in selected.value) selected.value - key else selected.value + key
    }

    val filtered = remember(allItems, query) {
        if (query.isBlank()) allItems
        else allItems.filter { it.contains(query, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("$habitName — graph items", fontSize = 15.sp)
                Text(
                    "Pick which blood test items appear as lines. " +
                        "\"Items tested\" counts everything measured that day.",
                    fontSize = 10.sp, color = Color(0xFF888888)
                )
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("search items…", fontSize = 11.sp) },
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(6.dp))
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    item {
                        FilterRow(
                            label = bloodMetricLabel(BLOOD_METRIC_COUNT),
                            checked = BLOOD_METRIC_COUNT in selected.value,
                            onToggle = { toggle(BLOOD_METRIC_COUNT) }
                        )
                    }
                    items(filtered) { name ->
                        val key = bloodMetricKey(name)
                        FilterRow(
                            label = name,
                            checked = key in selected.value,
                            onToggle = { toggle(key) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                viewModel.setBloodTestGraphSelection(habitName, selected.value)
                onDismiss()
            }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun FilterRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 12.sp, color = Color(0xFFCCCCCC), modifier = Modifier.weight(1f))
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = Color(0xFFBB88FF),
                uncheckedColor = Color(0xFF666666)
            )
        )
    }
}
