package com.example.samizdat

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RideIntentDao {
    @Query("SELECT * FROM ride_intents WHERE status = 'ACTIVE' ORDER BY departureTime ASC")
    fun getAllActiveIntents(): Flow<List<RideIntent>>

    @Query("SELECT COUNT(*) FROM ride_intents WHERE status = 'ACTIVE'")
    suspend fun getActiveIntentCount(): Int

    @Insert
    suspend fun insertIntent(intent: RideIntent): Long

    @Update
    suspend fun updateIntent(intent: RideIntent)

    @Delete
    suspend fun deleteIntent(intent: RideIntent)

    @Query("UPDATE ride_intents SET status = 'EXPIRED' WHERE departureTime < :currentTime AND status = 'ACTIVE'")
    suspend fun expireOldIntents(currentTime: Long)
}
