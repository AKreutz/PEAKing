package com.example.peaking.data.peak

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [VisitedPeak::class], version = 3, exportSchema = false)
abstract class PeakingDatabase : RoomDatabase() {
    abstract fun visitedPeakDao(): VisitedPeakDao

    companion object {
        @Volatile
        private var instance: PeakingDatabase? = null

        fun getInstance(context: Context): PeakingDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PeakingDatabase::class.java,
                    "peaking.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
