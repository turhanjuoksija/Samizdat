package com.example.samizdat

import android.util.Log
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Kademlia DHT Node implementation.
 * Uses XOR metric for distance calculation between node IDs and grid hashes.
 */
class KademliaNode(private val myOnionAddress: String) {
    
    companion object {
        private const val TAG = "KademliaNode"
        private const val K_BUCKET_SIZE = 20 // Standard Kademlia k value
        private const val HASH_BITS = 256 // SHA-256 is 256-bit
    }

    // My node ID derived from .onion address
    val nodeId: ByteArray = computeHash(myOnionAddress)
    val nodeIdHex: String = nodeId.toHexString()

    // Known peers: Map from nodeIdHex to onion address
    // FIX: Thread-safe map
    private val knownPeers = ConcurrentHashMap<String, String>()

    // Local storage: Map from gridIdHash to list of messages
    // FIX: Thread-safe map
    private val localStorage = ConcurrentHashMap<String, MutableList<GridMessage>>()

    /**
     * Data class for messages stored in the DHT
     */
    data class GridMessage(
        val gridId: String,
        val senderOnion: String,
        val senderNickname: String,
        val content: String,
        val timestamp: Long,
        val ttlSeconds: Int = 3600,
        // Route data for filtering and distance calculations
        val routePoints: List<Pair<Double, Double>> = emptyList(), // Tarkka tiereitti (lat, lon)
        val routeGrids: List<String> = emptyList(), // Ruutu-ID lista
        val originLat: Double? = null,  // Kuskin lähtöpaikka
        val originLon: Double? = null,
        val destLat: Double? = null,    // Kuskin määränpää
        val destLon: Double? = null,
        val availableSeats: Int = 0,
        val driverCurrentLat: Double? = null, // Kuskin NYKYINEN sijainti
        val driverCurrentLon: Double? = null
    )

    /**
     * Compute SHA-256 hash of input string
     */
    fun computeHash(input: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
    }

    /**
     * Convert Grid ID (e.g., "RG-3344-1456") to DHT-compatible hash
     */
    fun getGridHash(gridId: String): ByteArray {
        return computeHash(gridId)
    }

    /**
     * Calculate XOR distance between two hashes (Kademlia metric)
     * Returns the distance as a BigInteger for comparison
     */
    fun xorDistance(hash1: ByteArray, hash2: ByteArray): java.math.BigInteger {
        require(hash1.size == hash2.size) { "Hash sizes must match" }
        val xorResult = ByteArray(hash1.size)
        for (i in hash1.indices) {
            xorResult[i] = (hash1[i].toInt() xor hash2[i].toInt()).toByte()
        }
        return java.math.BigInteger(1, xorResult)
    }

    /**
     * Add a known peer to our routing table
     */
    fun addPeer(onionAddress: String) {
        val peerId = computeHash(onionAddress).toHexString()
        if (peerId != nodeIdHex) {
            knownPeers[peerId] = onionAddress
            Log.d(TAG, "Added peer: $onionAddress (ID: ${peerId.take(8)}...)")
        }
    }

    /**
     * Remove a peer from routing table
     */
    fun removePeer(onionAddress: String) {
        val peerId = computeHash(onionAddress).toHexString()
        knownPeers.remove(peerId)
    }

    /**
     * Find the k closest nodes to a given target hash
     */
    fun findClosestNodes(targetHash: ByteArray, k: Int = K_BUCKET_SIZE): List<String> {
        return knownPeers.entries
            .map { (peerIdHex, onion) ->
                val peerId = peerIdHex.hexToByteArray()
                val distance = xorDistance(peerId, targetHash)
                Triple(onion, peerIdHex, distance)
            }
            .sortedBy { it.third }
            .take(k)
            .map { it.first } // Return onion addresses
    }

    /**
     * Check if this node is responsible for storing data for a given grid
     * Returns true if we are the closest known node to this grid's hash
     */
    fun isResponsibleForGrid(gridId: String): Boolean {
        val gridHash = getGridHash(gridId)
        val myDistance = xorDistance(nodeId, gridHash)
        
        // Check if any known peer is closer
        for ((peerIdHex, _) in knownPeers) {
            val peerId = peerIdHex.hexToByteArray()
            val peerDistance = xorDistance(peerId, gridHash)
            if (peerDistance < myDistance) {
                return false // Someone else is closer
            }
        }
        return true // We are the closest (or tied)
    }

    /**
     * Store a message locally (for grids we are responsible for)
     */
    fun storeLocally(message: GridMessage) {
        val gridHashHex = getGridHash(message.gridId).toHexString()
        val messages = localStorage.getOrPut(gridHashHex) { mutableListOf() }
        messages.add(message)
        Log.d(TAG, "Stored message for grid ${message.gridId} (total: ${messages.size})")
    }

    /**
     * Retrieve locally stored messages for a grid
     */
    fun getLocalMessages(gridId: String): List<GridMessage> {
        val gridHashHex = getGridHash(gridId).toHexString()
        val messages = localStorage[gridHashHex] ?: emptyList()
        
        // Filter out expired messages
        val now = System.currentTimeMillis()
        return messages.filter { 
            (now - it.timestamp) < (it.ttlSeconds * 1000L) 
        }
    }

    /**
     * Get all grids we are currently storing data for
     */
    fun getStoredGrids(): Set<String> {
        return localStorage.values.flatten().map { it.gridId }.toSet()
    }

    /**
     * Clean up expired messages
     */
    fun pruneExpiredMessages() {
        val now = System.currentTimeMillis()
        localStorage.forEach { (_, messages) ->
            messages.removeAll { (now - it.timestamp) >= (it.ttlSeconds * 1000L) }
        }
        // Remove empty entries
        localStorage.entries.removeAll { it.value.isEmpty() }
    }

    // Extension functions for ByteArray/String conversion
    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
    
    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
