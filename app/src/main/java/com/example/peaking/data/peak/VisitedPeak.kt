package com.example.peaking.data.peak

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single recorded visit to a peak. A peak can be visited more than once, so [peakId] (the
 * peak's identity) is not the primary key - each visit gets its own row, keyed by [visitId].
 * Map tile features carry no stable OSM id in the data available to the app (only
 * name/elevation/coordinates), so [peakId] is derived from the peak's name and its coordinates
 * rounded to ~11m - see [visitedPeakId].
 */
@Entity(tableName = "visited_peaks")
data class VisitedPeak(
    @PrimaryKey(autoGenerate = true) val visitId: Long = 0,
    val peakId: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val visitedAtEpochMillis: Long,
    val visitDateEpochMillis: Long = visitedAtEpochMillis,
    val description: String = "",
    val elevationMeters: Double? = null
)

/**
 * Rounds to 4 decimal places (~11m at the equator) so that repeated lookups of the same peak
 * feature - which can jitter slightly between tile loads/zoom levels - resolve to the same id.
 */
fun visitedPeakId(name: String, latitude: Double, longitude: Double): String {
    val roundedLat = Math.round(latitude * 10_000.0) / 10_000.0
    val roundedLng = Math.round(longitude * 10_000.0) / 10_000.0
    return "$name@$roundedLat,$roundedLng"
}
