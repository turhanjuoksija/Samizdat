package com.example.samizdat

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerPublicKey: String,
    val content: String,
    val timestamp: Long,
    val isIncoming: Boolean,
    val status: String = "SENT", // SENT, DELIVERED, FAILED
    val type: String = "text", // "text", "ride_request", "ride_accept"
    val relatedOfferId: String? = null // For linking requests to offers
)
