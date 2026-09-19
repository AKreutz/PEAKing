package com.akreutz.peaking.ui.mypeaks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.akreutz.peaking.R
import com.akreutz.peaking.data.peak.VisitedPeak
import com.akreutz.peaking.data.peak.VisitedPeakRepository
import com.akreutz.peaking.ui.common.ScreenBackground
import com.akreutz.peaking.ui.map.CrownIcon
import com.akreutz.peaking.ui.map.formatElevationMeters
import com.akreutz.peaking.ui.theme.PEAKingTheme
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val ElevationBandSizeMeters = 500

private data class PeakGroup(
    val peakId: String,
    val name: String,
    val visits: List<VisitedPeak>
) {
    val lastVisitedEpochMillis: Long get() = visits.maxOf { it.visitDateEpochMillis }
    val elevationMeters: Double? get() = visits.firstNotNullOfOrNull { it.elevationMeters }
}

/**
 * A 500m elevation bucket ([rangeStartMeters], [rangeStartMeters] + 500), or `null` for peaks
 * with no recorded elevation - grouped into their own trailing section instead of being dropped.
 */
private data class ElevationSection(
    val rangeStartMeters: Int?,
    val peaks: List<PeakGroup>
)

private fun elevationSections(peakGroups: List<PeakGroup>): List<ElevationSection> {
    val (withElevation, withoutElevation) = peakGroups.partition { it.elevationMeters != null }
    val bands = withElevation
        .groupBy { group ->
            val elevation = group.elevationMeters!!
            (elevation / ElevationBandSizeMeters).toInt() * ElevationBandSizeMeters
        }
        .toSortedMap(compareByDescending { it })
        .map { (rangeStart, peaks) ->
            ElevationSection(rangeStart, peaks.sortedByDescending { it.elevationMeters })
        }
    return if (withoutElevation.isEmpty()) {
        bands
    } else {
        bands + ElevationSection(rangeStartMeters = null, peaks = withoutElevation.sortedByDescending { it.lastVisitedEpochMillis })
    }
}

@Composable
fun MyPeaksScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { VisitedPeakRepository(context) }
    val visitedPeaks by repository.observeVisitedPeaks().collectAsState(initial = emptyList())

    val peakGroups = remember(visitedPeaks) {
        visitedPeaks
            .groupBy { it.peakId }
            .map { (peakId, visits) -> PeakGroup(peakId, visits.first().name, visits) }
    }
    val sections = remember(peakGroups) { elevationSections(peakGroups) }

    var visitPendingEdit by remember { mutableStateOf<VisitedPeak?>(null) }
    var visitPendingDelete by remember { mutableStateOf<VisitedPeak?>(null) }

    ScreenBackground(modifier = modifier) {
        if (peakGroups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.my_peaks_empty),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = stringResource(
                        R.string.my_peaks_count,
                        peakGroups.size,
                        stringResource(
                            if (peakGroups.size == 1) R.string.my_peaks_peak_singular else R.string.my_peaks_peak_plural
                        )
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    sections.forEach { section ->
                        item(key = "header-${section.rangeStartMeters ?: "unknown"}") {
                            ElevationSectionHeader(section.rangeStartMeters)
                        }
                        items(section.peaks, key = { it.peakId }) { group ->
                            PeakGroupCard(
                                group = group,
                                onEditVisit = { visitPendingEdit = it }
                            )
                        }
                    }
                }
            }
        }
    }

    visitPendingEdit?.let { visit ->
        EditVisitDialog(
            visit = visit,
            onConfirm = { dateEpochMillis, description ->
                coroutineScope.launch {
                    repository.updateVisit(visit, dateEpochMillis, description)
                }
                visitPendingEdit = null
            },
            onDelete = {
                visitPendingEdit = null
                visitPendingDelete = visit
            },
            onDismiss = { visitPendingEdit = null }
        )
    }

    visitPendingDelete?.let { visit ->
        val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
        AlertDialog(
            onDismissRequest = { visitPendingDelete = null },
            title = { Text(text = stringResource(R.string.my_peaks_delete_dialog_title)) },
            text = {
                Text(
                    text = stringResource(
                        R.string.my_peaks_delete_dialog_message,
                        visit.name,
                        dateFormat.format(Date(visit.visitDateEpochMillis))
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    coroutineScope.launch { repository.deleteVisit(visit) }
                    visitPendingDelete = null
                }) {
                    Text(text = stringResource(R.string.my_peaks_delete_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { visitPendingDelete = null }) {
                    Text(text = stringResource(R.string.my_peaks_delete_dialog_cancel))
                }
            }
        )
    }
}

@Composable
private fun ElevationSectionHeader(rangeStartMeters: Int?) {
    val text = if (rangeStartMeters != null) {
        stringResource(R.string.my_peaks_elevation_range, rangeStartMeters, rangeStartMeters + ElevationBandSizeMeters)
    } else {
        stringResource(R.string.my_peaks_elevation_unknown)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
private fun PeakGroupCard(
    group: PeakGroup,
    onEditVisit: (VisitedPeak) -> Unit
) {
    var expanded by rememberSaveable(group.peakId) { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val sortedVisits = remember(group.visits) {
        group.visits.sortedByDescending { it.visitDateEpochMillis }
    }
    val chevronRotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "chevronRotation")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                CrownIcon(modifier = Modifier.size(20.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = group.name, style = MaterialTheme.typography.titleMedium)
                        group.elevationMeters?.let { elevationMeters ->
                            Text(
                                text = stringResource(R.string.peak_detail_elevation, formatElevationMeters(elevationMeters)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                    Text(
                        text = if (sortedVisits.size == 1) {
                            stringResource(
                                R.string.my_peaks_last_visited,
                                dateFormat.format(Date(group.lastVisitedEpochMillis))
                            )
                        } else {
                            stringResource(R.string.my_peaks_visit_count, sortedVisits.size, stringResource(R.string.my_peaks_visit_plural)) +
                                " · " +
                                stringResource(
                                    R.string.my_peaks_last_visited,
                                    dateFormat.format(Date(group.lastVisitedEpochMillis))
                                )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(chevronRotation)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    sortedVisits.forEachIndexed { index, visit ->
                        if (index > 0) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        }
                        VisitRow(
                            visit = visit,
                            onEdit = { onEditVisit(visit) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VisitRow(
    visit: VisitedPeak,
    onEdit: () -> Unit
) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = dateFormat.format(Date(visit.visitDateEpochMillis)),
                style = MaterialTheme.typography.bodyMedium
            )
            if (visit.description.isNotBlank()) {
                Text(
                    text = visit.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            } else {
                Text(
                    text = stringResource(R.string.my_peaks_no_description),
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.my_peaks_edit_visit)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditVisitDialog(
    visit: VisitedPeak,
    onConfirm: (dateEpochMillis: Long, description: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = visit.visitDateEpochMillis)
    var description by remember { mutableStateOf(visit.description) }
    var showDatePicker by remember { mutableStateOf(false) }

    val dateFormatter = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val selectedDateText = datePickerState.selectedDateMillis?.let { dateFormatter.format(Date(it)) } ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.my_peaks_edit_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = selectedDateText,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(text = stringResource(R.string.my_peaks_edit_dialog_date_label)) },
                    trailingIcon = {
                        IconButton(onClick = { showDatePicker = true }) {
                            Icon(imageVector = Icons.Filled.CalendarMonth, contentDescription = null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(text = stringResource(R.string.my_peaks_edit_dialog_description_label)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(datePickerState.selectedDateMillis ?: visit.visitDateEpochMillis, description)
                }
            ) {
                Text(text = stringResource(R.string.my_peaks_edit_dialog_confirm))
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(text = stringResource(R.string.my_peaks_edit_dialog_delete))
                }
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.my_peaks_edit_dialog_cancel))
                }
            }
        }
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(text = stringResource(R.string.my_peaks_edit_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(text = stringResource(R.string.my_peaks_edit_dialog_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MyPeaksScreenPreview() {
    PEAKingTheme {
        MyPeaksScreen()
    }
}
