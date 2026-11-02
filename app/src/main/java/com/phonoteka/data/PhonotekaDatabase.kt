package com.phonoteka.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TrackEntity::class, CandidateEntity::class],
    version = 2,
    exportSchema = true
)
abstract class PhonotekaDatabase : RoomDatabase() {

    abstract fun trackDao(): TrackDao

    companion object {
        @Volatile
        private var INSTANCE: PhonotekaDatabase? = null

        fun get(context: Context): PhonotekaDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?:                 Room.databaseBuilder(
                    context.applicationContext,
                    PhonotekaDatabase::class.java,
                    "phonoteka.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }

        /** Для тестов — in-memory база. */
        fun getInMemory(context: Context): PhonotekaDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                PhonotekaDatabase::class.java
            )
                .allowMainThreadQueries()
                .build()
        }
    }
}
