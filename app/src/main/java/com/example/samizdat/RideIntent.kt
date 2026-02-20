package com.example.samizdat

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ride_intents")
data class RideIntent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String, // "OFFER" (Driver) or "REQUEST" (Passenger)
    
    // Route Details
    val originLat: Double,
    val originLon: Double,
    val originAddress: String? = null, // Optional descriptive text
    
    val destLat: Double,
    val destLon: Double,
    val destAddress: String? = null,

    // Timing
    val departureTime: Long, // Epoch millis
    val flexibleTimeWindow: Long = 7200000, // +/- ms tolerance (default 2h)
    
    // Status
    val status: String = "ACTIVE", // ACTIVE, EXPIRED, CANCELLED
    val createdAt: Long = System.currentTimeMillis()
)
