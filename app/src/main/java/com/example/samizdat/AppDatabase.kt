package com.example.samizdat

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Peer::class, ChatMessage::class, VouchEntity::class, RideIntent::class], version = 13)
abstract class AppDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao
    abstract fun vouchDao(): VouchDao
    abstract fun rideIntentDao(): RideIntentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "samizdat_database"
                )
                .fallbackToDestructiveMigration(dropAllTables = true) // Wipes data on schema change
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
