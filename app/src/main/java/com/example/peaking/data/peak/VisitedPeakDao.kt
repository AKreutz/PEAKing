package com.example.peaking.data.peak

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VisitedPeakDao {
    @Query("SELECT * FROM visited_peaks")
    fun observeAll(): Flow<List<VisitedPeak>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(peak: VisitedPeak)

    @Update
    suspend fun update(peak: VisitedPeak)

    @Delete
    suspend fun delete(peak: VisitedPeak)
}
