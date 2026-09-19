package com.akreutz.peaking.data.peak

import android.content.Context
import kotlinx.coroutines.flow.Flow

class VisitedPeakRepository(context: Context) {
    private val dao = PeakingDatabase.getInstance(context).visitedPeakDao()

    fun observeVisitedPeaks(): Flow<List<VisitedPeak>> = dao.observeAll()

    suspend fun markVisited(
        name: String,
        latitude: Double,
        longitude: Double,
        visitDateEpochMillis: Long = System.currentTimeMillis(),
        description: String = "",
        elevationMeters: Double? = null
    ) {
        dao.insert(
            VisitedPeak(
                peakId = visitedPeakId(name, latitude, longitude),
                name = name,
                latitude = latitude,
                longitude = longitude,
                visitedAtEpochMillis = System.currentTimeMillis(),
                visitDateEpochMillis = visitDateEpochMillis,
                description = description,
                elevationMeters = elevationMeters
            )
        )
    }

    suspend fun updateVisit(
        visit: VisitedPeak,
        visitDateEpochMillis: Long,
        description: String
    ) {
        dao.update(
            visit.copy(
                visitDateEpochMillis = visitDateEpochMillis,
                description = description
            )
        )
    }

    suspend fun deleteVisit(visit: VisitedPeak) {
        dao.delete(visit)
    }
}
