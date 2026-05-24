package com.dezacodex.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        User::class,
        LoversProfile::class,
        LoveMessage::class,
        CoupleSnap::class,
        CoupleStory::class
    ],
    version = 3,
    exportSchema = false
)
abstract class HeartDatabase : RoomDatabase() {
    abstract fun heartDao(): HeartDao

    companion object {
        @Volatile
        private var INSTANCE: HeartDatabase? = null

        fun getDatabase(context: Context): HeartDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    HeartDatabase::class.java,
                    "heartsync_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
