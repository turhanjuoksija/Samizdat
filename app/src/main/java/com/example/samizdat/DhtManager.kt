package com.example.samizdat

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * DhtManager handles all DHT (Distributed Hash Table) related logic.
 * - Kademlia node initialization and management
 * - Grid message storage and retrieval
 * - Offer filtering for passengers
 * - Message broadcasting to grids
 */
class DhtManager(
    private val scope: CoroutineScope,
    private val onDebugLog: (String) -> Unit,
    private val getTorOnionAddress: () -> String?,
    private val getTorSocksPort: () -> Int,
    private val sendMessageToPeer: suspend (String, String, Int) -> Unit
) {
    // Kademlia DHT Node (initialized once we have an onion address)
    var kademliaNode: KademliaNode? = null
        private set
    
    // Grid messages received for the current grid
    private val _gridMessages = mutableStateListOf<KademliaNode.GridMessage>()
    val gridMessages: List<KademliaNode.GridMessage> = _gridMessages
    
    // Global/Route offers discovered via DHT
    private val _gridOffers = mutableStateListOf<KademliaNode.GridMessage>()
    val gridOffers: List<KademliaNode.GridMessage> = _gridOffers
    
    // Auto-derived listening radius from walking distance (set by PeersViewModel)
    var getListeningRadius: () -> Int = { 1 }
    
    // Callback to get current route grids from RouteManager
    var getRouteGrids: () -> List<String> = { emptyList() }
    var getCurrentGridId: () -> String? = { null }
    var getStoredPeers: suspend () -> List<Peer> = { emptyList() }

    /**
     * Initialize Kademlia DHT node once we have an onion address
     */
    fun initKademlia(onionAddress: String) {
        if (kademliaNode == null) {
            kademliaNode = KademliaNode(onionAddress)
            onDebugLog("DHT: Node initialized (ID: ${kademliaNode!!.nodeIdHex.take(8)}...)")
            startDhtSyncLoop()
        }
    }

    /**
     * Periodically fetch messages from grids we are interested in
     */
    private fun startDhtSyncLoop() {
        scope.launch {
            while (true) {
                val node = kademliaNode ?: break
                node.pruneExpiredMessages()
                
                val routeGrids = getRouteGrids()
                val currentGridId = getCurrentGridId()
                val baseGrids = (routeGrids + listOfNotNull(currentGridId)).distinct()
                
                val interestGrids = if (getListeningRadius() > 0) {
                    baseGrids.flatMap { grid -> 
                        RadioGridUtils.getNeighborGrids(grid, getListeningRadius()) 
                    }.distinct()
                } else {
                    baseGrids
                }
                
                if (interestGrids.isNotEmpty()) {
                    onDebugLog("DHT SYNC: Fetching ${interestGrids.size} grids (Radius: ${getListeningRadius()})")
                    interestGrids.forEach { gridId ->
                        val messages = node.getLocalMessages(gridId)
                        messages.forEach { msg ->
                            val isDuplicate = _gridOffers.any { 
                                it.timestamp == msg.timestamp && it.senderOnion == msg.senderOnion 
                            }
                            if (!isDuplicate) {
                                _gridOffers.add(0, msg)
                                onDebugLog("DHT DISCOVERY: New offer in $gridId")
                            }
                        }
                    }
                    
                    // Cleanup old offers (keep last 50)
                    if (_gridOffers.size > 50) {
                        val toRemove = _gridOffers.drop(50)
                        _gridOffers.removeAll(toRemove)
                    }
                }
                
                delay(15000) // sync every 15 seconds
            }
        }
    }

    /**
     * Send a message to a specific grid.
     * Forwards to all known peers via Tor for DHT distribution.
     */
    fun sendToGrid(
        content: String,
        myNickname: String,
        targetGridId: String?,
        timestamp: Long,
        routeManager: RouteManager,
        mySeats: Int
    ) {
        val gridId = targetGridId ?: getCurrentGridId() ?: return
        val node = kademliaNode ?: return
        val myOnion = getTorOnionAddress() ?: return

        val routePointsList = routeManager.roadRoute?.map { 
            Pair(it.latitude, it.longitude) 
        } ?: emptyList()

        val message = KademliaNode.GridMessage(
            gridId = gridId,
            senderOnion = myOnion,
            senderNickname = myNickname,
            content = content,
            timestamp = timestamp,
            routePoints = routePointsList,
            routeGrids = routeManager.routeGrids,
            originLat = routeManager.myLatitude,
            originLon = routeManager.myLongitude,
            destLat = routeManager.myDestLat,
            destLon = routeManager.myDestLon,
            availableSeats = mySeats,
            driverCurrentLat = routeManager.myLatitude,
            driverCurrentLon = routeManager.myLongitude
        )

        // Always store locally
        node.storeLocally(message)
        onDebugLog("DHT: Stored locally for $gridId")

        // Forward to all known peers via Tor
        scope.launch {
            val routePointsJson = JSONArray()
            routePointsList.forEach { (lat, lon) ->
                routePointsJson.put(JSONArray().apply { put(lat); put(lon) })
            }
            
            val routeGridsJson = JSONArray(routeManager.routeGrids)

            val dhtJson = JSONObject().apply {
                put("v", 2)
                put("type", "dht_store")
                put("grid_id", gridId)
                put("sender_onion", myOnion)
                put("sender_nick", myNickname)
                put("content", content)
                put("timestamp", message.timestamp)
                put("ttl", message.ttlSeconds)
                put("route_points", routePointsJson)
                put("route_grids", routeGridsJson)
                put("origin_lat", routeManager.myLatitude)
                put("origin_lon", routeManager.myLongitude)
                put("dest_lat", routeManager.myDestLat)
                put("dest_lon", routeManager.myDestLon)
                put("seats", mySeats)
                put("driver_lat", routeManager.myLatitude)
                put("driver_lon", routeManager.myLongitude)
            }.toString()

            val peers = getStoredPeers()
            peers.forEach { peer ->
                if (!peer.onion.isNullOrEmpty() && peer.onion != myOnion) {
                    try {
                        sendMessageToPeer(peer.onion, dhtJson, getTorSocksPort())
                        onDebugLog("DHT: Forwarded to ${peer.nickname}")
                    } catch (e: Exception) {
                        Log.e("DhtManager", "DHT forward failed to ${peer.nickname}: ${e.message}")
                    }
                }
            }
        }
        onDebugLog("DHT: Broadcasting to grid $gridId")
    }

    /**
     * Handle incoming DHT message from the network
     */
    fun handleDhtMessage(json: JSONObject) {
        val node = kademliaNode ?: return
        
        val gridId = json.optString("grid_id", "")
        val senderOnion = json.optString("sender_onion", "")
        val senderNick = MessageValidator.sanitizeString(json.optString("sender_nick", "Unknown"), MessageValidator.MAX_NICKNAME_LENGTH)
        val content = MessageValidator.sanitizeString(json.optString("content", ""), MessageValidator.MAX_CONTENT_LENGTH)
        val timestamp = json.optLong("timestamp", System.currentTimeMillis())
        val ttl = MessageValidator.clampTtl(json.optInt("ttl", 3600))

        // Validate required fields
        if (!MessageValidator.isValidGridId(gridId)) return
        if (content.isEmpty()) return
        if (!MessageValidator.isValidOnionAddress(senderOnion)) return
        if (!MessageValidator.isValidTimestamp(timestamp)) return

        // Parse route data with size limit
        val routePointsJson = json.optJSONArray("route_points")
        val routePoints = mutableListOf<Pair<Double, Double>>()
        if (routePointsJson != null) {
            val limit = minOf(routePointsJson.length(), MessageValidator.MAX_ROUTE_POINTS)
            for (i in 0 until limit) {
                val point = routePointsJson.optJSONArray(i)
                if (point != null && point.length() >= 2) {
                    val lat = point.getDouble(0)
                    val lon = point.getDouble(1)
                    if (MessageValidator.isValidLatitude(lat) && MessageValidator.isValidLongitude(lon)) {
                        routePoints.add(Pair(lat, lon))
                    }
                }
            }
        }
        
        val routeGridsJson = json.optJSONArray("route_grids")
        val routeGrids = mutableListOf<String>()
        if (routeGridsJson != null) {
            val limit = minOf(routeGridsJson.length(), MessageValidator.MAX_ROUTE_GRIDS)
            for (i in 0 until limit) {
                val g = routeGridsJson.optString(i, "")
                if (MessageValidator.isValidGridId(g)) {
                    routeGrids.add(g)
                }
            }
        }
        
        val rawOrigin = MessageValidator.validateCoordinate(
            if (json.has("origin_lat") && !json.isNull("origin_lat")) json.getDouble("origin_lat") else null,
            if (json.has("origin_lon") && !json.isNull("origin_lon")) json.getDouble("origin_lon") else null
        )
        val rawDest = MessageValidator.validateCoordinate(
            if (json.has("dest_lat") && !json.isNull("dest_lat")) json.getDouble("dest_lat") else null,
            if (json.has("dest_lon") && !json.isNull("dest_lon")) json.getDouble("dest_lon") else null
        )
        val rawDriver = MessageValidator.validateCoordinate(
            if (json.has("driver_lat") && !json.isNull("driver_lat")) json.getDouble("driver_lat") else null,
            if (json.has("driver_lon") && !json.isNull("driver_lon")) json.getDouble("driver_lon") else null
        )
        val seats = MessageValidator.clampSeats(json.optInt("seats", 0))

        val message = KademliaNode.GridMessage(
            gridId = gridId,
            senderOnion = senderOnion,
            senderNickname = senderNick,
            content = content,
            timestamp = timestamp,
            ttlSeconds = ttl,
            routePoints = routePoints,
            routeGrids = routeGrids,
            originLat = rawOrigin?.first,
            originLon = rawOrigin?.second,
            destLat = rawDest?.first,
            destLon = rawDest?.second,
            availableSeats = seats,
            driverCurrentLat = rawDriver?.first,
            driverCurrentLon = rawDriver?.second
        )
        
        // Store if we don't already have it (deduplicate by timestamp+sender)
        val existing = node.getLocalMessages(gridId)
        val isDuplicate = existing.any { it.timestamp == timestamp && it.senderOnion == senderOnion }
        
        if (!isDuplicate) {
            node.storeLocally(message)
            onDebugLog("DHT: Received from $senderNick for $gridId (${routeGrids.size} grids)")
            
            // Add sender as a known peer
            node.addPeer(senderOnion)
        }
    }

    /**
     * Get messages from a specific grid
     */
    fun getGridMessages(targetGridId: String? = null): List<KademliaNode.GridMessage> {
        val gridId = targetGridId ?: getCurrentGridId() ?: return emptyList()
        val node = kademliaNode ?: return emptyList()
        return node.getLocalMessages(gridId)
    }

    /**
     * Get active offers, deduplicated by sender (one offer per driver).
     * Keeps the latest offer based on timestamp.
     */
    fun getActiveOffers(): List<KademliaNode.GridMessage> {
        return gridOffers
            .groupBy { it.senderOnion }
            .map { (_, messages) -> messages.maxBy { it.timestamp } }
            .sortedBy { it.timestamp } // Optional: sort by time
    }

    /**
     * Get filtered offers for Passengers based on their route and walking distance.
     * Drivers see all active offers.
     */
    fun getFilteredOffers(
        role: String,
        myGridId: String?,
        myDestGridId: String?,
        maxWalkingDistanceMeters: Int
    ): List<KademliaNode.GridMessage> {
        val activeOffers = getActiveOffers()
        
        // Drivers see everything (deduplicated)
        if (role != "PASSENGER") return activeOffers
        
        val myStart = myGridId ?: return emptyList()
        val myDest = myDestGridId ?: return emptyList()
        
        // Calculate grid radius from walking distance (~2km per grid cell)
        val walkRadius = (maxWalkingDistanceMeters / 2000).coerceIn(0, 2)
        
        val startArea = RadioGridUtils.getNeighborGrids(myStart, walkRadius).toSet()
        val destArea = RadioGridUtils.getNeighborGrids(myDest, walkRadius).toSet()
        
        return activeOffers.filter { offer ->
            val routeGridsSet = offer.routeGrids.toSet()
            
            if (routeGridsSet.isEmpty()) {
                // Legacy fallback
                offer.gridId in startArea && offer.gridId in destArea
            } else {
                val passesNearStart = routeGridsSet.any { it in startArea }
                val passesNearDest = routeGridsSet.any { it in destArea }
                passesNearStart && passesNearDest
            }
        }
    }

    /**
     * Calculate walking distances for a ride offer.
     * Returns Pair(walkToPickup, walkFromDropoff) in meters.
     */
    fun calculateWalkDistances(
        offer: KademliaNode.GridMessage,
        startLat: Double?,
        startLon: Double?,
        destLat: Double?,
        destLon: Double?
    ): Pair<Int, Int> {
        if (startLat == null || startLon == null || destLat == null || destLon == null) {
            return Pair(0, 0)
        }
        
        if (offer.routePoints.isEmpty()) {
            return Pair(0, 0)
        }
        
        // Find closest point on driver's route to passenger's start
        var minPickupDist = Float.MAX_VALUE
        offer.routePoints.forEach { (lat, lon) ->
            val results = FloatArray(1)
            android.location.Location.distanceBetween(startLat, startLon, lat, lon, results)
            if (results[0] < minPickupDist) {
                minPickupDist = results[0]
            }
        }
        
        // Find closest point on driver's route to passenger's destination
        var minDropoffDist = Float.MAX_VALUE
        offer.routePoints.forEach { (lat, lon) ->
            val results = FloatArray(1)
            android.location.Location.distanceBetween(destLat, destLon, lat, lon, results)
            if (results[0] < minDropoffDist) {
                minDropoffDist = results[0]
            }
        }
        
        return Pair(minPickupDist.toInt(), minDropoffDist.toInt())
    }

    /**
     * DEBUG: Inject a fake offer for testing UI
     */
    fun addDebugOffer(myLatitude: Double?, myLongitude: Double?) {
        val lat = myLatitude ?: 60.1699
        val lon = myLongitude ?: 24.9384
        
        val driverLat = lat + (Math.random() - 0.5) * 0.01
        val driverLon = lon + (Math.random() - 0.5) * 0.01
        val destLat = lat + (Math.random() - 0.3) * 0.1
        val destLon = lon + (Math.random() - 0.3) * 0.1
        
        val gridId = RadioGridUtils.getGridId(driverLat, driverLon)
        val destGridId = RadioGridUtils.getGridId(destLat, destLon)
        val destinations = listOf("Helsinki", "Espoo", "Vantaa", "Tampere")
        val dest = destinations.random()
        
        val fakeRoutePoints = listOf(
            Pair(driverLat, driverLon),
            Pair((driverLat + destLat) / 2, (driverLon + destLon) / 2),
            Pair(destLat, destLon)
        )
        val fakeRouteGrids = fakeRoutePoints.map { 
            RadioGridUtils.getGridId(it.first, it.second) 
        }.distinct()
        
        val msg = KademliaNode.GridMessage(
            gridId = gridId,
            senderOnion = "debug_onion_${System.currentTimeMillis()}",
            senderNickname = "TestDriver_${(10..99).random()}",
            content = "RIDE OFFER: to $dest (3 seats). Leaving in 15min.",
            timestamp = System.currentTimeMillis(),
            routePoints = fakeRoutePoints,
            routeGrids = fakeRouteGrids,
            originLat = driverLat,
            originLon = driverLon,
            destLat = destLat,
            destLon = destLon,
            availableSeats = 3,
            driverCurrentLat = driverLat,
            driverCurrentLon = driverLon
        )
        
        _gridOffers.add(0, msg)
        onDebugLog("DEBUG: Added test offer in $gridId with route to $destGridId")
    }
}
