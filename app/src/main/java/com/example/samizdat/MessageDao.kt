package com.example.samizdat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE peerPublicKey = :peerPk ORDER BY timestamp ASC")
    fun getMessagesForPeer(peerPk: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM messages WHERE type = 'ride_request' AND isIncoming = 1 ORDER BY timestamp DESC")
    fun getRequestsForUs(): Flow<List<ChatMessage>>

    // Get list of unique peers we have chatted with, ordered by most recent message
    @Query("""
        SELECT * FROM messages 
        WHERE id IN (
            SELECT MAX(id) FROM messages 
            WHERE type != 'ride_request' AND type != 'status'
            GROUP BY peerPublicKey
        ) 
        ORDER BY timestamp DESC
    """)
    fun getRecentConversations(): Flow<List<ChatMessage>>

    @Insert
    suspend fun insertMessage(message: ChatMessage): Long

    @Update
    suspend fun updateMessage(message: ChatMessage)


}
