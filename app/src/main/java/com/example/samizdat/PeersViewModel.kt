package com.example.samizdat

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.util.Log
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class PeersViewModel(
    private val repository: PeerRepository,
    val nsdHelper: NsdHelper,
    val torManager: SamizdatTorManager,
    val updateManager: UpdateManager
) : ViewModel() {
    
    private var _myRole = mutableStateOf("NONE")
    
    var myRole: String
        get() = _myRole.value
        set(value) {
            if (_myRole.value != value) {
                _myRole.value = value
                // User requested: Clear route and stop broadcasting when role changes
                clearAll() 
                isBroadcasting = false
                syncRoleBasedRoute()
            }
        }
    
    var mySeats by mutableIntStateOf(3)
    var myInfo by mutableStateOf("")
    var myFullPubKey by mutableStateOf<String?>(null)
    
    // Driver Route
    private var driverDestLat: Double? = null
    private var driverDestLon: Double? = null
    // Waypoints now handled by myWaypoints list
    
    // Passenger Route
    private var passengerDestLat: Double? = null
    private var passengerDestLon: Double? = null
    
    var myLatitude by mutableStateOf<Double?>(null)
    var myLongitude by mutableStateOf<Double?>(null)
    var myDestLat by mutableStateOf<Double?>(null)
    var isSetLocationMode by mutableStateOf(false)
    var myDestLon by mutableStateOf<Double?>(null)

    // Multi-Waypoint Support
    var myWaypoints = mutableStateListOf<org.osmdroid.util.GeoPoint>()
    
    var myGridId by mutableStateOf<String?>(null)
    var myDestGridId by mutableStateOf<String?>(null)
    var isWaypointMode by mutableStateOf(false)
    var listeningRadius by mutableIntStateOf(1) // Expand listening to neighbors (Radius 1 = 9 cells)
    var maxWalkingDistanceMeters by mutableIntStateOf(500) // Passenger: max walk to/from pickup (default 500m)
    var isBroadcasting by mutableStateOf(false)
    var myNickname by mutableStateOf("Me")
    
    // Kademlia DHT Node (initialized once we have an onion address)
    var kademliaNode: KademliaNode? = null
        private set
    
    // Grid messages received for the current grid
    private val _gridMessages = mutableStateListOf<KademliaNode.GridMessage>()
    val gridMessages: List<KademliaNode.GridMessage> = _gridMessages
    
    // Global/Route offers discovered via DHT
    private val _gridOffers = mutableStateListOf<KademliaNode.GridMessage>()
    val gridOffers: List<KademliaNode.GridMessage> = _gridOffers
    
    // Road route for map display (calculated via OSRM)
    var roadRoute by mutableStateOf<List<org.osmdroid.util.GeoPoint>?>(null)
        private set
    var routeGrids by mutableStateOf<List<String>>(emptyList())
        private set
    
    // Debug Log (For UI visibility)
    private val _debugLogs = mutableStateListOf<String>()
    val debugLogs: List<String> = _debugLogs

    val storedPeers: Flow<List<Peer>> = repository.storedPeers
    val allVouches: Flow<List<VouchEntity>> = repository.allVouches
    val incomingMessages = repository.incomingMessages
    val incomingRequests = repository.incomingRequestsForUs
    val recentConversations = repository.recentConversations
    
    // Tor State (Exposing from TorManager)
    val torStatus = torManager.statusMessage
    val torOnionAddress = torManager.onionAddress
    val isTorBootstrapped = torManager.isBootstrapped

    init {
        try {
            // Start server when VM is created - REQUIRED for Tor Hidden Service to work
            repository.startServer(viewModelScope)
            // Start Tor
            torManager.startTor()
        } catch (e: Throwable) {
            Log.e("PeersViewModel", "Error in init", e)
        }
        
        // Background collection of incoming messages for persistence
        viewModelScope.launch {
            repository.incomingMessages.collect { (senderIp, rawMsg) ->
                handleIncomingMessage(senderIp, rawMsg)
            }
        }
        
        // Collect Tor logs for UI
    viewModelScope.launch {
            torManager.statusMessage.collect { msg ->
                if (msg.isNotEmpty()) {
                    _debugLogs.add(0, "${System.currentTimeMillis()}: $msg")
                }
            }
        }

        // Initialize Kademlia when Tor is ready
        viewModelScope.launch {
            torOnionAddress.collect { onion ->
                if (onion != null) {
                    initKademlia(onion)
                }
            }
        }

        // Automatic Status Broadcast Loop
        viewModelScope.launch {
            while (true) {
                if (isBroadcasting) {
                    broadcastStatus(myNickname)
                }
                delay(30000) // every 30 seconds
            }
        }
    }

    private suspend fun handleIncomingMessage(senderIp: String, rawMsg: String) {
        var senderOnion = senderIp 
        var messageText = rawMsg
        var senderNick: String? = null

        try {
            if (rawMsg.startsWith("{")) {
                val json = org.json.JSONObject(rawMsg)
                if (json.has("v")) {
                    senderOnion = json.optString("f_onion", senderIp)
                    senderNick = if (json.isNull("f_nick")) null else json.optString("f_nick", null)
                    messageText = json.optString("p", "")

                    val newRole = json.optString("role", "NONE")
                    val newSeats = json.optInt("seats", 0)
                    val newInfo = json.optString("info", "")
                    val newLat = if (json.has("lat") && !json.isNull("lat")) json.getDouble("lat") else null
                    val newLon = if (json.has("lon") && !json.isNull("lon")) json.getDouble("lon") else null
                    val newDLat = if (json.has("d_lat") && !json.isNull("d_lat")) json.getDouble("d_lat") else null
                    val newDLon = if (json.has("d_lon") && !json.isNull("d_lon")) json.getDouble("d_lon") else null

                    val type = json.optString("type", "text")
                    val incomingPubKey = json.optString("p", null)
                    
                    if (type == "dht_store") {
                        handleDhtMessage(json)
                        return
                    }
                    
                    if (type == "vouch") {
                        handleVouchMessage(json)
                        return
                    }

                    if (type == "APP_UPDATE") {
                        handleUpdateMessage(json)
                        return
                    }
                    
                    if (type == "status") messageText = ""

                    val lookupId = OnionUtils.ensureOnionSuffix(senderOnion)
                    val currentPeers = storedPeers.first()
                    val resolvedPeer = currentPeers.find { it.publicKey == lookupId || it.onion == lookupId }

                    if (resolvedPeer != null) {
                        val statusChanged = (resolvedPeer.role != newRole || resolvedPeer.seats != newSeats || resolvedPeer.extraInfo != newInfo)
                        val nickChanged = (senderNick != null && resolvedPeer.nickname != senderNick)
                        val locChanged = (newLat != null && newLat != resolvedPeer.latitude) || (newLon != null && newLon != resolvedPeer.longitude)
                        val destChanged = (newDLat != null && newDLat != resolvedPeer.destLat) || (newDLon != null && newDLon != resolvedPeer.destLon)
                        val pkChanged = (incomingPubKey != null && incomingPubKey != resolvedPeer.fullPublicKey)
                        
                        if (statusChanged || nickChanged || locChanged || destChanged || pkChanged) {
                            savePeer(
                                lastKnownIp = senderIp,
                                nickname = senderNick ?: resolvedPeer.nickname,
                                publicKey = resolvedPeer.publicKey,
                                onion = resolvedPeer.onion,
                                role = newRole,
                                seats = newSeats,
                                info = newInfo,
                                lat = newLat,
                                lon = newLon,
                                dLat = newDLat,
                                dLon = newDLon,
                                fullPk = incomingPubKey ?: resolvedPeer.fullPublicKey
                            )
                        }
                    } else {
                        savePeer(lookupId, senderNick ?: "Unknown", lookupId, lookupId, newRole, newSeats, newInfo, newLat, newLon, newDLat, newDLon, incomingPubKey)
                    }

                    // Save Message
                    if (messageText.isNotEmpty()) {
                        val finalPk = resolvedPeer?.publicKey ?: lookupId
                        repository.saveMessage(ChatMessage(
                            peerPublicKey = finalPk,
                            content = messageText,
                            timestamp = System.currentTimeMillis(),
                            isIncoming = true,
                            status = "DELIVERED",
                            type = type
                        ))
                        val logPrefix = if (type == "ride_request") "RIDE REQUEST" else "INBOUND"
                        _debugLogs.add(0, "$logPrefix from ${senderNick ?: resolvedPeer?.nickname ?: finalPk}: $messageText")
                    } else if (type == "status") {
                         _debugLogs.add(0, "STATUS UPDATE from ${senderNick ?: resolvedPeer?.nickname ?: lookupId}")
                    }
                } else {
                    senderOnion = json.optString("f", senderIp)
                    messageText = json.optString("m", rawMsg)
                }
            }
        } catch (e: Exception) {
            Log.e("PeersViewModel", "Error parsing incoming msg", e)
        }
    }

    fun getMessagesForPeer(peerPk: String): Flow<List<ChatMessage>> {
        return repository.getMessagesForPeer(peerPk)
    }

    fun registerService(nickname: String, hash: String) {
        val onion = torManager.onionAddress.value
        repository.registerMyService(12345, nickname, hash, myRole, mySeats, myInfo, onion)
    }

    fun updateMyStatus(nickname: String, hash: String) {
        val onion = torManager.onionAddress.value
        repository.stopDiscovery()
        repository.registerMyService(12345, nickname, hash, myRole, mySeats, myInfo, onion)
        repository.startDiscovery()
    }

    fun startDiscovery() {
        repository.startDiscovery()
    }

    fun stopDiscovery() {
        repository.stopDiscovery()
    }

    fun savePeer(
        lastKnownIp: String, 
        nickname: String, 
        publicKey: String, 
        onion: String? = null,
        role: String? = null,
        seats: Int? = null,
        info: String? = null,
        lat: Double? = null,
        lon: Double? = null,
        dLat: Double? = null,
        dLon: Double? = null,
        fullPk: String? = null
    ) {
        viewModelScope.launch {
            val finalIp = OnionUtils.ensureOnionSuffix(lastKnownIp)
            val finalOnion = if (onion != null) OnionUtils.ensureOnionSuffix(onion) else (if (OnionUtils.isOnion(finalIp)) finalIp else null)
            
            val existingPeer = repository.getPeerByOnion(finalOnion ?: finalIp)
            val vouchCount = repository.getVouchCount(publicKey)
            
            val peerToSave = if (existingPeer != null) {
                existingPeer.copy(
                    nickname = nickname,
                    lastKnownIp = finalIp,
                    lastSeenTimestamp = System.currentTimeMillis(),
                    role = role ?: existingPeer.role,
                    seats = seats ?: existingPeer.seats,
                    extraInfo = info ?: existingPeer.extraInfo,
                    onion = finalOnion,
                    latitude = lat ?: existingPeer.latitude,
                    longitude = lon ?: existingPeer.longitude,
                    destLat = dLat ?: existingPeer.destLat,
                    destLon = dLon ?: existingPeer.destLon,
                    fullPublicKey = fullPk ?: existingPeer.fullPublicKey,
                    reputationScore = vouchCount
                )
            } else {
                val finalPublicKey = if ((onion != null || finalIp.endsWith(".onion")) && publicKey.startsWith("manual_")) {
                    onion ?: finalIp
                } else {
                    publicKey
                }
                
                Peer(
                    publicKey = finalPublicKey,
                    nickname = nickname,
                    lastKnownIp = finalIp,
                    isTrusted = true,
                    lastSeenTimestamp = System.currentTimeMillis(),
                    onion = finalOnion,
                    role = role ?: "NONE",
                    seats = seats ?: 0,
                    extraInfo = info ?: "",
                    latitude = lat,
                    longitude = lon,
                    destLat = dLat,
                    destLon = dLon,
                    reputationScore = vouchCount,
                    fullPublicKey = fullPk
                )
            }
            repository.savePeer(peerToSave)
        }
    }

    fun deletePeer(peer: Peer) {
        viewModelScope.launch {
            repository.deletePeer(peer)
        }
    }

    fun sendMessage(peer: Peer, message: String, myNickname: String, type: String = "text") {
        viewModelScope.launch {
            // 1. Initial save with PENDING status
            val initialMsg = ChatMessage(
                peerPublicKey = peer.publicKey,
                content = message,
                timestamp = System.currentTimeMillis(),
                isIncoming = false,
                status = "PENDING",
                type = type
            )
            val msgId = repository.saveMessage(initialMsg)

            try {
                _debugLogs.add(0, "OUTBOUND to ${peer.nickname}: Connecting...")
                
                // Samizdat Envelope v1
                val myOnion = torManager.onionAddress.value ?: "unknown"
                val json = org.json.JSONObject().apply {
                    put("v", 1)
                    put("f_onion", myOnion)
                    put("f_nick", myNickname)
                    put("role", myRole)
                    put("seats", mySeats)
                    put("info", myInfo)
                    put("lat", myLatitude)
                    put("lon", myLongitude)
                    put("d_lat", myDestLat)
                    put("d_lon", myDestLon)
                    put("t", System.currentTimeMillis())
                    put("type", type)
                    put("p", message)
                }
                val payload = json.toString()
                
                // 2. Perform network send & wait for ACK
                val currentSocksPort = torManager.socksPort.value ?: 9050
                repository.sendMessage(peer.lastKnownIp, payload, currentSocksPort)
                
                // 3. Update to DELIVERED (if it didn't throw)
                repository.updateMessage(initialMsg.copy(id = msgId, status = "DELIVERED"))
                _debugLogs.add(0, "OUTBOUND to ${peer.nickname}: DELIVERED")
            } catch (e: Exception) {
                // 4. Update to FAILED
                repository.updateMessage(initialMsg.copy(id = msgId, status = "FAILED"))
                _debugLogs.add(0, "ERROR sending to ${peer.nickname}: ${e.message}")
            }
        }
    }

    fun acceptRideRequest(requestMsg: ChatMessage) {
        viewModelScope.launch {
            val peer = storedPeers.first().find { it.publicKey == requestMsg.peerPublicKey }
            if (peer != null) {
                sendMessage(
                    peer = peer,
                    message = "Kyyti hyväksytty! 🚕 Olen tulossa.",
                    myNickname = myNickname,
                    type = "ride_accept"
                )
                
                // Decrement available seats
                mySeats = (mySeats - 1).coerceAtLeast(0)
                _debugLogs.add(0, "Ride accepted. Seats remaining: $mySeats")
                
                // Stop broadcasting if no more seats available
                if (mySeats == 0 && isBroadcasting) {
                    isBroadcasting = false
                    _debugLogs.add(0, "BROADCAST STOPPED: No more seats available")
                }
            } else {
                _debugLogs.add(0, "ERROR: Peer not found for request")
            }
        }
    }

    fun declineRideRequest(requestMsg: ChatMessage) {
         viewModelScope.launch {
            val peer = storedPeers.first().find { it.publicKey == requestMsg.peerPublicKey }
            if (peer != null) {
                sendMessage(
                    peer = peer,
                    message = "Valitettavasti en pääse nyt. 🚫",
                    myNickname = myNickname,
                    type = "ride_decline"
                )
            }
        }
    }

    fun addManualPeer(onion: String, localNickname: String) {
        val cleanOnion = OnionUtils.ensureOnionSuffix(onion)
        // 1. Save locally with trusted flag
        savePeer(
            lastKnownIp = cleanOnion, 
            nickname = localNickname, 
            publicKey = cleanOnion, // Use onion as temp PK
            onion = cleanOnion, 
            role = "NONE", 
            seats = 0, 
            info = "Manual Add"
        )
        
        // 2. Trigger Handshake (Send our status to them)
        viewModelScope.launch {
            try {
                // Allow a small delay for save to propagate if needed, though not strictly required
                delay(500) 
                val payload = getMyStatusPayload()
                val currentSocksPort = torManager.socksPort.value ?: 9050
                repository.sendMessage(cleanOnion, payload, currentSocksPort)
                _debugLogs.add(0, "HANDSHAKE: Sent Hello to $localNickname")
            } catch (e: Exception) {
                _debugLogs.add(0, "HANDSHAKE FAILED: ${e.message}")
            }
        }
    }

    private fun getMyStatusPayload(): String {
        val myOnion = torManager.onionAddress.value ?: "unknown"
        return org.json.JSONObject().apply {
            put("v", 1)
            put("f_onion", myOnion)
            put("f_nick", myNickname)
            put("role", myRole)
            put("seats", mySeats)
            put("info", myInfo)
            put("lat", myLatitude)
            put("lon", myLongitude)
            put("d_lat", myDestLat)
            put("d_lon", myDestLon)
            put("t", System.currentTimeMillis())
            put("type", "status")
            put("p", myFullPubKey ?: "") 
        }.toString()
    }

    fun broadcastStatus(myNickname: String) {
        viewModelScope.launch {
            val payload = getMyStatusPayload()

            _debugLogs.add(0, "BROADCASTING STATUS: $myRole ($mySeats seats) @ $myLatitude, $myLongitude")

            // Send to ALL trusted peers
            // Get current snapshot of peers from Flow
            val currentPeers = storedPeers.first()
            
            currentPeers.forEach { peer ->
                // Only send if we have a way to reach them (onion or ip)
                if (peer.isTrusted || peer.onion != null) {
                    launch {
                        try {
                            val currentSocksPort = torManager.socksPort.value ?: 9050
                            repository.sendMessage(peer.lastKnownIp, payload, currentSocksPort)
                            Log.i("PeersViewModel", "Status sent to ${peer.nickname}")
                        } catch (e: Exception) {
                            Log.w("PeersViewModel", "Failed to send status to ${peer.nickname}")
                        }
                    }
                }
            }

            // Automated DHT Broadcast for Drivers
            if (myRole == "DRIVER" && routeGrids.isNotEmpty()) {
                val statusInfo = if (myInfo.isNotEmpty()) ": $myInfo" else ""
                val dhtContent = "RIDE OFFER: $myNickname ($mySeats seats)$statusInfo. Dest: $myDestLat, $myDestLon"
                
                val commonTimestamp = System.currentTimeMillis()
                
                launch {
                    val node = kademliaNode
                    if (node != null) {
                        routeGrids.forEach { gridId ->
                            sendToGrid(dhtContent, myNickname, gridId, commonTimestamp)
                        }
                        _debugLogs.add(0, "DHT: Route broadcast to ${routeGrids.size} grids")
                    }
                }
            }
        }
    }

    fun updateLocation(lat: Double, lon: Double) {
        myLatitude = lat
        myLongitude = lon
        // Update current grid ID
        myGridId = RadioGridUtils.getGridId(lat, lon)
    }

    fun updateDestination(lat: Double, lon: Double) {
        myDestLat = lat
        myDestLon = lon
        myDestGridId = RadioGridUtils.getGridId(lat, lon)
        
        if (myRole == "DRIVER") {
            driverDestLat = lat; driverDestLon = lon
            // If driver sets a "Destination" explicitly (maybe via search?), add it to waypoints?
            // Or just keep it as the final point.
            // For now, let's keep myDestLat as the official "End" point.
        } else {
            passengerDestLat = lat; passengerDestLon = lon
            // Passenger clears waypoints, only one dest
            myWaypoints.clear()
        }
        _debugLogs.add(0, "DESTINATION SET: $lat, $lon ($myDestGridId)")
    }

    fun updateWaypoint(lat: Double, lon: Double, onMaxReached: (() -> Unit)? = null) {
        // Request 5: Turn off broadcasting when adding a new point
        if (isBroadcasting) {
            isBroadcasting = false
            _debugLogs.add(0, "BROADCAST PAUSED (Route Changed)")
        }

        if (myRole == "DRIVER") {
            // For Drivers, we append waypoints to build a route
            if (myWaypoints.size < 6) {
                myWaypoints.add(org.osmdroid.util.GeoPoint(lat, lon))
                _debugLogs.add(0, "ADDED STOP ${myWaypoints.size}: $lat, $lon")
                
                // For Drivers, the last point is the current "Destination" for broadcasting/logic
                myDestLat = lat
                myDestLon = lon
                myDestGridId = RadioGridUtils.getGridId(lat, lon)
            } else {
                 _debugLogs.add(0, "MAX WAYPOINTS REACHED (6)")
                 onMaxReached?.invoke()
            }
        } else {
            // For Passengers, we treat any added point as the *Single* destination
            // (clearing previous ones)
            updateDestination(lat, lon)
        }
    }

    fun clearAll() {
        myDestLat = null
        myDestLon = null
        myDestGridId = null
        myWaypoints.clear()
        roadRoute = null
        routeGrids = emptyList()
        // Stop broadcasting since destination is gone
        isBroadcasting = false
        
        // Clear persisted state regarding ALL roles to ensure clean switch
        driverDestLat = null
        driverDestLon = null
        passengerDestLat = null
        passengerDestLon = null
        
        _debugLogs.add(0, "ALL POINTS CLEARED")
    }

    fun clearWaypoint() {
        // Request 4: Turn off broadcasting when undoing
        if (isBroadcasting) {
            isBroadcasting = false
             _debugLogs.add(0, "BROADCAST PAUSED (Route Changed)")
        }

        if (myWaypoints.isNotEmpty()) {
            // Request 6: removeLast() correctly removes the last added item (tail)
            myWaypoints.removeLast() // Undo last add
            
            // Sync destination to the new last waypoint
            if (myRole == "DRIVER") {
                if (myWaypoints.isNotEmpty()) {
                    val last = myWaypoints.last()
                    myDestLat = last.latitude
                    myDestLon = last.longitude
                    myDestGridId = RadioGridUtils.getGridId(last.latitude, last.longitude)
                } else {
                    myDestLat = null
                    myDestLon = null
                    myDestGridId = null
                }
            }
            
            _debugLogs.add(0, "REMOVED LAST WAYPOINT")
        } else {
            myDestLat = null
            myDestLon = null
            myDestGridId = null
            _debugLogs.add(0, "DESTINATION CLEARED")
        }
        calculateRoute()
    }

    private fun syncRoleBasedRoute() {
        // Simple logic: we clear the active route when switching roles to avoid confusion.
        // Persistence can be re-added later if needed.
        myDestLat = null
        myDestLon = null
        myWaypoints.clear()
        
        if (myRole == "DRIVER") {
            // Restore driver state if we want persistence (optional, current plan says clear)
        } else if (myRole == "PASSENGER") {
            // Restore passenger state
        }
        myDestGridId = null
        calculateRoute()
    }

    /**
     * Calculate road route from current location to destination using OSRM
     */
    /**
     * Calculate road route from current location -> [waypoints] -> destination
     */
    fun calculateRoute() {
        val startLat = myLatitude ?: return
        val startLon = myLongitude ?: return
        
        // Passengers: Start -> Dest
        // Drivers: Start -> WP1 -> WP2 -> ... -> WP_Last (Last one acts as destination)
        
        val pointsToVisit = mutableListOf<org.osmdroid.util.GeoPoint>()
        pointsToVisit.add(org.osmdroid.util.GeoPoint(startLat, startLon))
        pointsToVisit.addAll(myWaypoints)
        
        // If passenger, we might still have myDestLat/Lon distinct from waypoints
        // But for Driver, the points ARE the waypoints.
        // Let's keep consistent: if myDestLat is set, it's the FINAL point.
        // CHECK: If myDestLat is just the last waypoint (synced), don't add it twice.
        if (myDestLat != null && myDestLon != null) {
             val lastWp = if (myWaypoints.isNotEmpty()) myWaypoints.last() else null
             val isSameAsLast = lastWp != null && lastWp.latitude == myDestLat && lastWp.longitude == myDestLon
             
             if (!isSameAsLast) {
                 pointsToVisit.add(org.osmdroid.util.GeoPoint(myDestLat!!, myDestLon!!))
             }
        }

        if (pointsToVisit.size < 2) {
            roadRoute = null
            routeGrids = emptyList()
            // _debugLogs.add(0, "ROUTE: Cleared (Not enough points)") // Optional logging
            return
        }

        viewModelScope.launch {
            _debugLogs.add(0, "ROUTE: Calculating ${pointsToVisit.size} stops...")
            val fullRoute = mutableListOf<org.osmdroid.util.GeoPoint>()
            
            // Calculate segments
            for (i in 0 until pointsToVisit.size - 1) {
                val segmentStart = pointsToVisit[i]
                val segmentEnd = pointsToVisit[i+1]
                val segmentRoute = RoutingService.getRoute(segmentStart, segmentEnd, null)
                
                if (segmentRoute != null) {
                     // specific for osmdroid: don't duplicate the join point?
                     fullRoute.addAll(segmentRoute)
                }
            }

            if (fullRoute.isNotEmpty()) {
                roadRoute = fullRoute
                routeGrids = RadioGridUtils.getRouteGridsFromPolyline(fullRoute)
                _debugLogs.add(0, "ROUTE: Total ${fullRoute.size} pts, ${routeGrids.size} grids")
            } else {
                _debugLogs.add(0, "ROUTE: Failed to calculate")
            }
        }
    }

    /**
     * Initialize Kademlia DHT node once we have an onion address
     */
    fun initKademlia(onionAddress: String) {
        if (kademliaNode == null) {
            kademliaNode = KademliaNode(onionAddress)
            _debugLogs.add(0, "DHT: Node initialized (ID: ${kademliaNode!!.nodeIdHex.take(8)}...)")
            startDhtSyncLoop()
        }
    }

    /**
     * Periodically fetch messages from grids we are interested in (Current Grid + Route Grids)
     */
    private fun startDhtSyncLoop() {
        viewModelScope.launch {
            while (true) {
                val node = kademliaNode ?: break
                node.pruneExpiredMessages() // Clean up old data
                val baseGrids = (routeGrids + listOfNotNull(myGridId)).distinct()
                val interestGrids = if (listeningRadius > 0) {
                    baseGrids.flatMap { grid -> RadioGridUtils.getNeighborGrids(grid, listeningRadius) }.distinct()
                } else {
                    baseGrids
                }
                
                if (interestGrids.isNotEmpty()) {
                    _debugLogs.add(0, "DHT SYNC: Fetching ${interestGrids.size} grids (Radius: $listeningRadius)")
                    interestGrids.forEach { gridId ->
                        // In a real DHT, we would query peers here.
                        // For now, we "sync" by updating our local state from the KademliaNode storage
                        // which is populated by incoming 'dht_store' messages from Tor.
                        val messages = node.getLocalMessages(gridId)
                        messages.forEach { msg ->
                            val isDuplicate = _gridOffers.any { it.timestamp == msg.timestamp && it.senderOnion == msg.senderOnion }
                            if (!isDuplicate) {
                                _gridOffers.add(0, msg)
                                _debugLogs.add(0, "DHT DISCOVERY: New offer in $gridId")
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
     * Send a message to the current grid (or a specific grid).
     * Forwards to all known peers via Tor for DHT distribution.
     */
    fun sendToGrid(content: String, myNickname: String, targetGridId: String? = null, timestamp: Long = System.currentTimeMillis()) {
        val gridId = targetGridId ?: myGridId ?: return
        val node = kademliaNode ?: return
        val myOnion = torOnionAddress.value ?: return

        // Convert roadRoute to list of coordinate pairs
        val routePointsList = roadRoute?.map { Pair(it.latitude, it.longitude) } ?: emptyList()

        val message = KademliaNode.GridMessage(
            gridId = gridId,
            senderOnion = myOnion,
            senderNickname = myNickname,
            content = content,
            timestamp = timestamp,
            routePoints = routePointsList,
            routeGrids = routeGrids,
            originLat = myLatitude,
            originLon = myLongitude,
            destLat = myDestLat,
            destLon = myDestLon,
            availableSeats = mySeats,
            driverCurrentLat = myLatitude,
            driverCurrentLon = myLongitude
        )

        // Always store locally
        node.storeLocally(message)
        _debugLogs.add(0, "DHT: Stored locally for $gridId")

        // Forward to all known peers via Tor
        viewModelScope.launch {
            // Build route points JSON array
            val routePointsJson = org.json.JSONArray()
            routePointsList.forEach { (lat, lon) ->
                routePointsJson.put(org.json.JSONArray().apply { put(lat); put(lon) })
            }
            
            // Build route grids JSON array
            val routeGridsJson = org.json.JSONArray(routeGrids)

            val dhtJson = org.json.JSONObject().apply {
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
                put("origin_lat", myLatitude)
                put("origin_lon", myLongitude)
                put("dest_lat", myDestLat)
                put("dest_lon", myDestLon)
                put("seats", mySeats)
                put("driver_lat", myLatitude)
                put("driver_lon", myLongitude)
            }.toString()

            storedPeers.first().forEach { peer ->
                if (!peer.onion.isNullOrEmpty() && peer.onion != myOnion) {
                    try {
                        val currentSocksPort = torManager.socksPort.value ?: 9050
                        repository.sendMessage(peer.onion!!, dhtJson, currentSocksPort)
                        _debugLogs.add(0, "DHT: Forwarded to ${peer.nickname}")
                    } catch (e: Exception) {
                        Log.e("PeersViewModel", "DHT forward failed to ${peer.nickname}: ${e.message}")
                    }
                }
            }
        }
        _debugLogs.add(0, "DHT: Broadcasting to grid $gridId")
    }

    /**
     * DEBUG: Inject a fake offer for testing UI
     */
    fun addDebugOffer() {
        val lat = myLatitude ?: 60.1699
        val lon = myLongitude ?: 24.9384
        // Create a random nearby location for driver start
        val driverLat = lat + (Math.random() - 0.5) * 0.01
        val driverLon = lon + (Math.random() - 0.5) * 0.01
        
        // Create a destination point further away
        val destLat = lat + (Math.random() - 0.3) * 0.1
        val destLon = lon + (Math.random() - 0.3) * 0.1
        
        val gridId = RadioGridUtils.getGridId(driverLat, driverLon)
        val destGridId = RadioGridUtils.getGridId(destLat, destLon)
        val destinations = listOf("Helsinki", "Espoo", "Vantaa", "Tampere")
        val dest = destinations.random()
        
        // Create a simple route from driver location to destination
        val fakeRoutePoints = listOf(
            Pair(driverLat, driverLon),
            Pair((driverLat + destLat) / 2, (driverLon + destLon) / 2),
            Pair(destLat, destLon)
        )
        val fakeRouteGrids = fakeRoutePoints.map { RadioGridUtils.getGridId(it.first, it.second) }.distinct()
        
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
        _debugLogs.add(0, "DEBUG: Added test offer in $gridId with route to $destGridId")
    }

    /**
     * Handle incoming DHT message from the network
     */
    private fun handleDhtMessage(json: org.json.JSONObject) {
        val node = kademliaNode ?: return
        
        val gridId = json.optString("grid_id", "")
        val senderOnion = json.optString("sender_onion", "")
        val senderNick = json.optString("sender_nick", "Unknown")
        val content = json.optString("content", "")
        val timestamp = json.optLong("timestamp", System.currentTimeMillis())
        val ttl = json.optInt("ttl", 3600)
        
        // Parse route data
        val routePointsJson = json.optJSONArray("route_points")
        val routePoints = mutableListOf<Pair<Double, Double>>()
        if (routePointsJson != null) {
            for (i in 0 until routePointsJson.length()) {
                val point = routePointsJson.optJSONArray(i)
                if (point != null && point.length() >= 2) {
                    routePoints.add(Pair(point.getDouble(0), point.getDouble(1)))
                }
            }
        }
        
        val routeGridsJson = json.optJSONArray("route_grids")
        val routeGrids = mutableListOf<String>()
        if (routeGridsJson != null) {
            for (i in 0 until routeGridsJson.length()) {
                routeGrids.add(routeGridsJson.getString(i))
            }
        }
        
        val originLat = if (json.has("origin_lat") && !json.isNull("origin_lat")) json.getDouble("origin_lat") else null
        val originLon = if (json.has("origin_lon") && !json.isNull("origin_lon")) json.getDouble("origin_lon") else null
        val destLat = if (json.has("dest_lat") && !json.isNull("dest_lat")) json.getDouble("dest_lat") else null
        val destLon = if (json.has("dest_lon") && !json.isNull("dest_lon")) json.getDouble("dest_lon") else null
        val seats = json.optInt("seats", 0)
        val driverLat = if (json.has("driver_lat") && !json.isNull("driver_lat")) json.getDouble("driver_lat") else null
        val driverLon = if (json.has("driver_lon") && !json.isNull("driver_lon")) json.getDouble("driver_lon") else null

        if (gridId.isEmpty() || content.isEmpty()) return
        
        val message = KademliaNode.GridMessage(
            gridId = gridId,
            senderOnion = senderOnion,
            senderNickname = senderNick,
            content = content,
            timestamp = timestamp,
            ttlSeconds = ttl,
            routePoints = routePoints,
            routeGrids = routeGrids,
            originLat = originLat,
            originLon = originLon,
            destLat = destLat,
            destLon = destLon,
            availableSeats = seats,
            driverCurrentLat = driverLat,
            driverCurrentLon = driverLon
        )
        
        // Store if we don't already have it (deduplicate by timestamp+sender)
        val existing = node.getLocalMessages(gridId)
        val isDuplicate = existing.any { it.timestamp == timestamp && it.senderOnion == senderOnion }
        
        if (!isDuplicate) {
            node.storeLocally(message)
            _debugLogs.add(0, "DHT: Received from $senderNick for $gridId (${routeGrids.size} grids)")
            
            // Add sender as a known peer
            node.addPeer(senderOnion)
        }
    }

    /**
     * Get messages from the current grid
     */
    fun getGridMessages(targetGridId: String? = null): List<KademliaNode.GridMessage> {
        val gridId = targetGridId ?: myGridId ?: return emptyList()
        val node = kademliaNode ?: return emptyList()
        return node.getLocalMessages(gridId)
    }

    /**
     * Get filtered offers for Passengers based on their route and walking distance preference.
     * Drivers see all offers (for debugging/awareness).
     * 
     * An offer is shown if the driver's route passes through BOTH:
     * 1. The passenger's start area (within walking distance)
     * 2. The passenger's destination area (within walking distance)
     */
    fun getFilteredOffers(): List<KademliaNode.GridMessage> {
        // Drivers see everything (for now)
        if (myRole != "PASSENGER") return gridOffers
        
        val myStart = myGridId ?: return emptyList()
        val myDest = myDestGridId ?: return emptyList()
        
        // Calculate grid radius from walking distance (~2km per grid cell)
        val walkRadius = (maxWalkingDistanceMeters / 2000).coerceIn(0, 2)
        
        val startArea = RadioGridUtils.getNeighborGrids(myStart, walkRadius).toSet()
        val destArea = RadioGridUtils.getNeighborGrids(myDest, walkRadius).toSet()
        
        return gridOffers.filter { offer ->
            // Check if the offer's route passes near BOTH our start AND destination
            val routeGridsSet = offer.routeGrids.toSet()
            
            // If no route grids available, fall back to checking the single gridId
            if (routeGridsSet.isEmpty()) {
                // Legacy/fallback: at least check if the single grid is in both areas
                offer.gridId in startArea && offer.gridId in destArea
            } else {
                // Check if any route grid is in start area AND any route grid is in dest area
                val passesNearStart = routeGridsSet.any { it in startArea }
                val passesNearDest = routeGridsSet.any { it in destArea }
                passesNearStart && passesNearDest
            }
        }
    }

    /**
     * Calculate walking distances for a ride offer.
     * Returns Pair(walkToPickup, walkFromDropoff) in meters.
     * Uses straight-line (haversine) distance as per user requirement.
     */
    fun calculateWalkDistances(offer: KademliaNode.GridMessage): Pair<Int, Int> {
        val startLat = myLatitude ?: return Pair(0, 0)
        val startLon = myLongitude ?: return Pair(0, 0)
        val destLat = myDestLat ?: return Pair(0, 0)
        val destLon = myDestLon ?: return Pair(0, 0)
        
        if (offer.routePoints.isEmpty()) {
            // No route data available, return 0
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
     * Handle incoming vouch message. Verifies signature and saves to DB.
     */
    private suspend fun handleVouchMessage(json: org.json.JSONObject) {
        val targetPk = json.optString("target", "")
        val voucherNick = json.optString("v_name", "Unknown")
        val sig = json.optString("sig", "")
        val timestamp = json.optLong("t", 0)
        val voucherOnion = json.optString("f_onion", "")
        val voucherFullPk = json.optString("p", "")

        if (targetPk.isEmpty() || sig.isEmpty() || voucherOnion.isEmpty() || voucherFullPk.isEmpty()) return

        // Verify Signature
        val dataToVerify = "$targetPk|$timestamp"
        val pubKeyBytes = android.util.Base64.decode(voucherFullPk, android.util.Base64.NO_WRAP)
        
        val isValid = CryptoUtils.verifySignature(dataToVerify, sig, pubKeyBytes)
        
        if (isValid) {
            _debugLogs.add(0, "WoT: Trusted Vouch from $voucherNick for $targetPk")
            repository.saveVouch(VouchEntity(voucherOnion, targetPk, sig, timestamp))
            
            // Trigger reputation update for the target peer
            val peers = storedPeers.first()
            val targetPeer = peers.find { it.publicKey == targetPk || it.onion == targetPk }
            targetPeer?.let { 
                savePeer(it.lastKnownIp, it.nickname, it.publicKey, it.onion, it.role, it.seats, it.extraInfo, it.latitude, it.longitude, it.destLat, it.destLon, it.fullPublicKey)
            }
        } else {
            _debugLogs.add(0, "WoT: REJECTED invalid Vouch from $voucherNick")
        }
    }

    /**
     * Vouch for a peer. Signs and broadcasts to the network.
     */
    fun vouchForPeer(peer: Peer) {
        viewModelScope.launch {
            val timestamp = System.currentTimeMillis()
            val dataToSign = "${peer.publicKey}|$timestamp"
            val signature = CryptoUtils.signData(dataToSign) ?: return@launch
            
            val vouchJson = org.json.JSONObject().apply {
                put("v", 2)
                put("type", "vouch")
                put("target", peer.publicKey)
                put("v_name", "Me") // Nickname sent by myNickname state usually
                put("sig", signature)
                put("t", timestamp)
                put("f_onion", torOnionAddress.value ?: "")
                put("p", myFullPubKey ?: "")
            }.toString()

            _debugLogs.add(0, "WoT: Vouching for ${peer.nickname}...")
            
            // Save locally so it shows in our "Given" stats
            repository.saveVouch(VouchEntity(torOnionAddress.value ?: "me", peer.publicKey, signature, timestamp))
            
            // Update local reputation score for this peer
            savePeer(peer.lastKnownIp, peer.nickname, peer.publicKey, peer.onion, peer.role, peer.seats, peer.extraInfo, peer.latitude, peer.longitude, peer.destLat, peer.destLon, peer.fullPublicKey)

            // Broadcast vouch to all known peers
            storedPeers.first().forEach { p ->
                if (!p.onion.isNullOrEmpty()) {
                    try {
                        repository.sendMessage(p.onion, vouchJson)
                    } catch (e: Exception) {
                        Log.e("PeersViewModel", "Failed to send vouch to ${p.nickname}")
                    }
                }
            }
        }
    }

    private fun handleUpdateMessage(json: org.json.JSONObject) {
        val version = json.optInt("ver", -1)
        val url = json.optString("url", "")
        val sig = json.optString("sig", "")
        
        // In real app, check 'version > BuildConfig.VERSION_CODE'
        if (version > 0 && url.isNotEmpty() && sig.isNotEmpty()) {
            _debugLogs.add(0, "UPDATE: Found version $version. Downloading...")
            viewModelScope.launch {
                try {
                    updateManager.downloadAndInstall(url, sig, version)
                    _debugLogs.add(0, "UPDATE: Install Prompted!")
                } catch (e: Exception) {
                    _debugLogs.add(0, "UPDATE ERROR: ${e.message}")
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopServer()
        repository.stopDiscovery()
        torManager.stopTor()
    }
}

class PeersViewModelFactory(
    private val repository: PeerRepository, 
    private val nsdHelper: NsdHelper,
    private val torManager: SamizdatTorManager,
    private val updateManager: UpdateManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PeersViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PeersViewModel(repository, nsdHelper, torManager, updateManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
