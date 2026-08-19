package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.tail.data.HabitSearchResult
import com.example.tail.data.HabitSearchSource
import java.time.format.DateTimeFormatter

private val SEARCH_DATE_FMT = DateTimeFormatter.ofPattern("EEE MMM d yyyy")

/**
 * Global search popup: searches all text-bearing habits (text entries, meal
 * logs, dated-entry files, habit notes) with fuzzy matching.
 *
 * All state (query, filters, results) lives in the [HabitViewModel], so
 * closing the dialog — including by tapping a result — preserves its exact
 * state for the next time the search icon is pressed. The last query and
 * filter selection are additionally persisted and restored across app
 * restarts. "All" re-selects every habit; "None" deselects them all.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HabitSearchDialog(
    viewModel: HabitViewModel,
    onDismiss: () -> Unit,
    onResultClick: (HabitSearchResult) -> Unit
) {
    val query by viewModel.searchQuery.collectAsState()
    val results by viewModel.searchResults.collectAsState()
    val filters by viewModel.searchFilters.collectAsState()
    val searchable by viewModel.searchableHabits.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()

    var filtersExpanded by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF161616),
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
            ) {
                // ── Search field ────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = Color(0xFF66CCFF)
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::updateSearchQuery,
                        placeholder = {
                            Text(
                                "Search text entries, meals, notes…",
                                fontSize = 13.sp,
                                color = Color(0xFF666666)
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF66CCFF),
                            unfocusedBorderColor = Color(0xFF444444),
                            cursorColor = Color(0xFF66CCFF)
                        ),
                        textStyle = TextStyle(fontSize = 14.sp)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close search",
                            tint = Color(0xFF888888)
                        )
                    }
                }

                // ── Filter section ──────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { filtersExpanded = !filtersExpanded },
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text(
                            text = (if (filtersExpanded) "▾ " else "▸ ") +
                                "Filters (${filters.size}/${searchable.size})",
                            color = Color(0xFF66CCFF),
                            fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = viewModel::clearSearchFilters,
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("None", color = Color(0xFFFF8888), fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = viewModel::setAllSearchFilters,
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("All", color = Color(0xFF88FF88), fontSize = 12.sp)
                    }
                }
                if (filtersExpanded) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (searchable.isEmpty()) {
                            Text(
                                "No habits with searchable text yet",
                                color = Color(0xFF666666),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        } else {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                searchable.forEach { info ->
                                    SearchFilterChip(
                                        label = info.habitName,
                                        selected = info.habitName in filters,
                                        onClick = { viewModel.toggleSearchFilter(info.habitName) }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)
                }

                if (isSearching) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF66CCFF),
                        trackColor = Color(0xFF222222)
                    )
                }

                // ── Results ─────────────────────────────────────────────────
                when {
                    query.isBlank() -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Type to search across text entries,\nmeals, dated entries and notes",
                                color = Color(0xFF666666),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    results.isEmpty() && !isSearching -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No matches for \"$query\"",
                                color = Color(0xFF666666),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(results) { _, result ->
                                SearchResultRow(result = result) {
                                    onResultClick(result)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A small toggleable chip for one habit in the filter section. */
@Composable
private fun SearchFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) Color(0xFF0A3A5A) else Color(0xFF262626)
    val border = if (selected) Color(0xFF66CCFF) else Color(0xFF444444)
    val fg = if (selected) Color(0xFF99DDFF) else Color(0xFF888888)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** One search hit: matched snippet (highlighted), habit name, source and date. */
@Composable
private fun SearchResultRow(
    result: HabitSearchResult,
    onClick: () -> Unit
) {
    val dateLabel = result.date?.format(SEARCH_DATE_FMT) ?: "no date"
    val snippet = buildAnnotatedString {
        append(result.snippetText)
        val s = result.matchStart.coerceIn(0, result.snippetText.length)
        val e = result.matchEnd.coerceIn(0, result.snippetText.length)
        if (e > s) {
            addStyle(
                SpanStyle(color = Color(0xFFFFD700), fontWeight = FontWeight.Bold),
                s, e
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E1E))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = snippet,
            color = Color(0xFFCCCCCC),
            fontSize = 12.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = result.habitName,
                color = Color(0xFFFFAA00),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = sourceLabel(result.source),
                color = Color(0xFF66CCFF),
                fontSize = 10.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = dateLabel,
                color = Color(0xFFAAAAAA),
                fontSize = 10.sp
            )
        }
    }
}

private fun sourceLabel(source: HabitSearchSource): String = when (source) {
    HabitSearchSource.TEXT_ENTRY -> "text"
    HabitSearchSource.MEAL_LOG -> "meal"
    HabitSearchSource.HABIT_NOTE -> "note"
    HabitSearchSource.DATED_ENTRY -> "entry"
}
