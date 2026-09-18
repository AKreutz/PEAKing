package com.example.peaking.ui.mypeaks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.peaking.R
import com.example.peaking.data.peak.VisitedPeak
import com.example.peaking.data.peak.VisitedPeakRepository
import com.example.peaking.ui.theme.PEAKingTheme
import java.text.DateFormat
import java.util.Date

@Composable
fun MyPeaksScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { VisitedPeakRepository(context) }
    val visitedPeaks by repository.observeVisitedPeaks().collectAsState(initial = emptyList())
    val sortedPeaks = remember(visitedPeaks) {
        visitedPeaks.sortedByDescending { it.visitedAtEpochMillis }
    }

    if (sortedPeaks.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.my_peaks_empty),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxSize()) {
            items(sortedPeaks, key = { it.id }) { peak ->
                VisitedPeakRow(peak)
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun VisitedPeakRow(peak: VisitedPeak) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(text = peak.name, style = MaterialTheme.typography.titleMedium)
        Text(
            text = dateFormat.format(Date(peak.visitedAtEpochMillis)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun MyPeaksScreenPreview() {
    PEAKingTheme {
        MyPeaksScreen()
    }
}
