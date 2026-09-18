package com.example.peaking.data.peak

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A peak the user has marked as visited. Map tile features carry no stable OSM id in the data
 * available to the app (only name/elevation/coordinates), so [id] is derived from the peak's
 * name and its coordinates rounded to ~11m - see [visitedPeakId].
 */
@Entity(tableName = "visited_peaks")
data class VisitedPeak(
    @PrimaryKey val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val visitedAtEpochMillis: Long
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
