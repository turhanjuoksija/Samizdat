package com.example.samizdat

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class Peer(
    @PrimaryKey val publicKey: String,
    val nickname: String,
    val lastKnownIp: String,
    val isTrusted: Boolean,
    val lastSeenTimestamp: Long,
    // Ride Sharing Extensions
    val role: String = "NONE", // DRIVER, PASSENGER, NONE
    val seats: Int = 0,
    val extraInfo: String = "", // e.g. "Home -> Work"
    val onion: String? = null, // .onion address for Tor connectivity
    // Location
    val latitude: Double? = null,
    val longitude: Double? = null,
    val destLat: Double? = null,
    val destLon: Double? = null,
    val reputationScore: Int = 0, // Trust level / vouchers count
    val fullPublicKey: String? = null // Base64 encoded actual public key for WoT
)
