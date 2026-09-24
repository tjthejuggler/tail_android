package com.example.tail.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Searchable multi-select habit picker popup. Replaces the long inline
 * checkbox lists in the Integrations sections: a single button opens this
 * dialog, whose search field filters the habit list live (case-insensitive
 * substring). "Clear" empties the selection; "Done" applies it.
 */
@Composable
fun SearchableHabitListDialog(
    title: String,
    habits: List<String>,
    selected: Set<String>,
    onApply: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(selected) }

    val filtered = remember(query, habits) {
        if (query.isBlank()) habits
        else habits.filter { it.contains(query.trim(), ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Search habits…", fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "${draft.size} selected" +
                        (if (filtered.size != habits.size) " · ${filtered.size} shown" else ""),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    itemsIndexed(filtered) { i, name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    draft = if (name in draft) draft - name else draft + name
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = name in draft,
                                onCheckedChange = {
                                    draft = if (it) draft + name else draft - name
                                }
                            )
                            Text(name, fontSize = 14.sp)
                        }
                        if (i < filtered.lastIndex) HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButtonClear(onClick = { draft = emptySet() })
                Button(
                    onClick = {
                        onApply(draft)
                        onDismiss()
                    }
                ) { Text("Done") }
            }
        }
    )
}

@Composable
private fun TextButtonClear(onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text("Clear")
    }
}
