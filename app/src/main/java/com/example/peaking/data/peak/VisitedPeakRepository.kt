package com.example.peaking.data.peak

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
        description: String = ""
    ) {
        dao.insert(
            VisitedPeak(
                peakId = visitedPeakId(name, latitude, longitude),
                name = name,
                latitude = latitude,
                longitude = longitude,
                visitedAtEpochMillis = System.currentTimeMillis(),
                visitDateEpochMillis = visitDateEpochMillis,
                description = description
            )
        )
    }

    /**
     * Removes every recorded visit for this peak, so it goes back to being fully unvisited.
     */
    suspend fun markNotVisited(name: String, latitude: Double, longitude: Double) {
        dao.deleteAllByPeakId(visitedPeakId(name, latitude, longitude))
    }
}
