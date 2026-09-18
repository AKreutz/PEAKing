package com.example.peaking.ui.hike

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.peaking.R
import com.example.peaking.data.peak.VisitedPeakRepository
import com.example.peaking.ui.common.ScreenBackground
import com.example.peaking.ui.map.CrownIcon
import com.example.peaking.ui.map.HikeSelectedPeak
import com.example.peaking.ui.map.PeakMap
import com.example.peaking.ui.theme.PEAKingTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

private const val ButtonFadeOutDurationMillis = 400
private const val ButtonFadeOutDelayMillis = 700
private const val MapFadeInDurationMillis = 500
private const val MapFadeInDelayMillis = 800

@Composable
private fun SelectedPeaksCard(peakNames: List<String>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .width(200.dp)
            .animateContentSize(),
        shape = RectangleShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            peakNames.forEach { name ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    CrownIcon(modifier = Modifier.size(16.dp).align(Alignment.CenterVertically))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .align(Alignment.CenterVertically),
                        textAlign = TextAlign.Left
                    )
                }
            }
        }
    }
}

@Composable
fun HikeScreen(
    modifier: Modifier = Modifier,
    onHikeInProgressChanged: (Boolean) -> Unit = {},
    discardRequested: Boolean = false,
    onDiscardHandled: () -> Unit = {}
) {
    var hikeStarted by remember { mutableStateOf(false) }
    var showConfetti by remember { mutableStateOf(false) }
    var selectedPeaks by remember { mutableStateOf<List<HikeSelectedPeak>>(emptyList()) }
    var showFinishDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val repository = remember { VisitedPeakRepository(context) }

    fun discardHike() {
        hikeStarted = false
        showConfetti = false
        selectedPeaks = emptyList()
        showFinishDialog = false
    }

    LaunchedEffect(hikeStarted) {
        onHikeInProgressChanged(hikeStarted)
    }

    LaunchedEffect(discardRequested) {
        if (discardRequested) {
            discardHike()
            onDiscardHandled()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = hikeStarted,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(
                animationSpec = tween(
                    durationMillis = MapFadeInDurationMillis,
                    delayMillis = MapFadeInDelayMillis
                )
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PeakMap(
                    modifier = Modifier.fillMaxSize(),
                    selectionMode = true,
                    selectedPeaks = selectedPeaks.toSet(),
                    onPeakToggled = { peak ->
                        selectedPeaks = if (selectedPeaks.any { it.id == peak.id }) {
                            selectedPeaks.filterNot { it.id == peak.id }
                        } else {
                            selectedPeaks + peak
                        }
                    }
                )

                Button(
                    onClick = {
                        if (selectedPeaks.isEmpty()) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.hike_finish_no_peaks_message),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            showFinishDialog = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedPeaks.isEmpty()) {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(24.dp)
                        .width(220.dp)
                        .height(72.dp)
                ) {
                    Text(
                        text = stringResource(R.string.hike_finish_hike),
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                if (selectedPeaks.isNotEmpty()) {
                    SelectedPeaksCard(
                        peakNames = selectedPeaks.map { it.name },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(16.dp)
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = !hikeStarted,
            modifier = Modifier.fillMaxSize(),
            exit = fadeOut(
                animationSpec = tween(
                    durationMillis = ButtonFadeOutDurationMillis,
                    delayMillis = ButtonFadeOutDelayMillis
                )
            )
        ) {
            ScreenBackground {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = {
                            showConfetti = true
                            hikeStarted = true
                        },
                        modifier = Modifier
                            .width(220.dp)
                            .height(72.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.hike_go_on_a_hike),
                            style = MaterialTheme.typography.titleLarge
                        )
                    }

                    if (showConfetti) {
                        ConfettiBurst(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }

        if (showFinishDialog) {
            FinishHikeDialog(
                onConfirm = { dateEpochMillis, description ->
                    val peaksToSave = selectedPeaks
                    coroutineScope.launch {
                        peaksToSave.forEach { peak ->
                            repository.markVisited(
                                name = peak.name,
                                latitude = peak.latitude,
                                longitude = peak.longitude,
                                visitDateEpochMillis = dateEpochMillis,
                                description = description,
                                elevationMeters = peak.elevationMeters
                            )
                        }
                    }
                    discardHike()
                },
                onDismiss = { discardHike() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FinishHikeDialog(
    onConfirm: (dateEpochMillis: Long, description: String) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    var description by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }

    val dateFormatter = remember { SimpleDateFormat("dd MMM yyyy", Locale.getDefault()) }
    val selectedDateText = datePickerState.selectedDateMillis?.let { dateFormatter.format(it) } ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.hike_finish_dialog_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = selectedDateText,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(text = stringResource(R.string.hike_finish_dialog_date_label)) },
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
                    label = { Text(text = stringResource(R.string.hike_finish_dialog_description_label)) },
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
                    onConfirm(datePickerState.selectedDateMillis ?: System.currentTimeMillis(), description)
                }
            ) {
                Text(text = stringResource(R.string.hike_finish_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.hike_finish_dialog_cancel))
            }
        }
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(text = stringResource(R.string.hike_finish_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(text = stringResource(R.string.hike_finish_dialog_cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HikeScreenPreview() {
    PEAKingTheme {
        HikeScreen()
    }
}
