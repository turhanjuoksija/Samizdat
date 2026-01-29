package com.example.samizdat

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeenTimestamp DESC")
    fun getAllPeers(): Flow<List<Peer>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: Peer)

    @Query("SELECT * FROM peers WHERE onion = :onion LIMIT 1")
    suspend fun getPeerByOnion(onion: String): Peer?



    @Delete
    suspend fun deletePeer(peer: Peer)
}
