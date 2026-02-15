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

/**
 * PeersViewModel - The main ViewModel for the Samizdat application.
 * 
 * This is now a thin orchestration layer that delegates to specialized managers:
 * - RouteManager: Handles all routing, GPS, and waypoint logic
 * - DhtManager: Handles DHT/Kademlia network operations
 * 
 * Messaging logic remains here for now (could be extracted to MessagingManager later).
 */
class PeersViewModel(
    private val repository: PeerRepository,
    val torManager: SamizdatTorManager,
    val updateManager: UpdateManager
) : ViewModel() {
    
    // ========== MANAGERS ==========
    
    /** Route Manager - handles all routing, GPS, and waypoint logic */
    val routeManager = RouteManager(
        scope = viewModelScope,
        onDebugLog = { msg -> _debugLogs.add(0, msg) }
    )
    
    /** DHT Manager - handles Kademlia/DHT operations */
    val dhtManager = DhtManager(
        scope = viewModelScope,
        onDebugLog = { msg -> _debugLogs.add(0, msg) },
        getTorOnionAddress = { torManager.onionAddress.value },
        getTorSocksPort = { torManager.socksPort.value ?: 9050 },
        sendMessageToPeer = { target, message, socksPort ->
            repository.sendMessage(target, message, socksPort)
        }
    )
    
    // ========== ROLE & USER STATE ==========
    
    private var _myRole = mutableStateOf("NONE")
    
    var myRole: String
        get() = _myRole.value
        set(value) {
            if (_myRole.value != value) {
                _myRole.value = value
                // User requested: Clear route and stop broadcasting when role changes
                routeManager.clearAll()
                isBroadcasting = false
                routeManager.syncRoleBasedRoute()
            }
        }
    
    var mySeats by mutableIntStateOf(3)
    var myInfo by mutableStateOf("")
    var myFullPubKey by mutableStateOf<String?>(null)
    var myNickname by mutableStateOf("Me")
    var isBroadcasting by mutableStateOf(false)
    var isSetLocationMode by mutableStateOf(false)
    var isWaypointMode by mutableStateOf(false)
    
    // ========== DELEGATED PROPERTIES (from RouteManager) ==========
    
    // These are convenience accessors to RouteManager state for backward compatibility
    val myLatitude: Double? get() = routeManager.myLatitude
    val myLongitude: Double? get() = routeManager.myLongitude
    val myDestLat: Double? get() = routeManager.myDestLat
    val myDestLon: Double? get() = routeManager.myDestLon
    val myGridId: String? get() = routeManager.myGridId
    val myDestGridId: String? get() = routeManager.myDestGridId
    val myWaypoints get() = routeManager.myWaypoints
    val roadRoute: List<org.osmdroid.util.GeoPoint>? get() = routeManager.roadRoute
    val routeGrids: List<String> get() = routeManager.routeGrids
    var maxWalkingDistanceMeters: Int
        get() = routeManager.maxWalkingDistanceMeters
        set(value) { routeManager.maxWalkingDistanceMeters = value }
    
    // ========== DEBUG LOG ==========
    
    private val _debugLogs = mutableStateListOf<String>()
    val debugLogs: List<String> = _debugLogs

    // ========== REPOSITORY FLOWS ==========
    
    val storedPeers: Flow<List<Peer>> = repository.storedPeers
    val allVouches: Flow<List<VouchEntity>> = repository.allVouches
    val incomingMessages = repository.incomingMessages
    val incomingRequests = repository.incomingRequestsForUs
    val recentConversations = repository.recentConversations
    
    // ========== TOR STATE ==========
    
    val torStatus = torManager.statusMessage
    val torOnionAddress = torManager.onionAddress
    val isTorBootstrapped = torManager.isBootstrapped

    // ========== INITIALIZATION ==========

    init {
        try {
            // Start server when VM is created - REQUIRED for Tor Hidden Service to work
            repository.startServer(viewModelScope)
            // Start Tor
            torManager.startTor()
        } catch (e: Throwable) {
            Log.e("PeersViewModel", "Error in init", e)
        }
        
        // Setup manager callbacks
        routeManager.onRouteChanged = {
            if (isBroadcasting) {
                isBroadcasting = false
                _debugLogs.add(0, "BROADCAST PAUSED (Route Changed)")
            }
        }
        
        dhtManager.getRouteGrids = { routeManager.routeGrids }
        dhtManager.getCurrentGridId = { routeManager.myGridId }
        dhtManager.getStoredPeers = { storedPeers.first() }
        dhtManager.getListeningRadius = {
            if (myRole == "PASSENGER") {
                kotlin.math.ceil(maxWalkingDistanceMeters / 500.0).toInt().coerceIn(1, 5)
            } else {
                1 // Drivers/NONE: 1 neighbor ring around route
            }
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
                    dhtManager.initKademlia(onion)
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

    // ========== INCOMING MESSAGE HANDLING ==========

    private suspend fun handleIncomingMessage(senderIp: String, rawMsg: String) {
        try {
            if (rawMsg.startsWith("{")) {
                val json = org.json.JSONObject(rawMsg)
                
                // Only process messages with version/format flag "v"
                if (json.has("v")) {
                    val senderOnion = json.optString("f_onion", senderIp)
                    var senderNick = json.optString("f_nick", "").takeIf { it.isNotEmpty() }
                    var messageText = json.optString("p", "")

                    // --- Input Validation ---
                    senderNick = senderNick?.let { MessageValidator.sanitizeString(it, MessageValidator.MAX_NICKNAME_LENGTH) }
                    messageText = MessageValidator.sanitizeString(messageText, MessageValidator.MAX_CONTENT_LENGTH)

                    val newRole = MessageValidator.sanitizeRole(json.optString("role", "NONE"))
                    val newSeats = MessageValidator.clampSeats(json.optInt("seats", 0))
                    val newInfo = MessageValidator.sanitizeString(json.optString("info", ""), MessageValidator.MAX_INFO_LENGTH)

                    val rawLat = if (json.has("lat") && !json.isNull("lat")) json.getDouble("lat") else null
                    val rawLon = if (json.has("lon") && !json.isNull("lon")) json.getDouble("lon") else null
                    val rawDLat = if (json.has("d_lat") && !json.isNull("d_lat")) json.getDouble("d_lat") else null
                    val rawDLon = if (json.has("d_lon") && !json.isNull("d_lon")) json.getDouble("d_lon") else null

                    val loc = MessageValidator.validateCoordinate(rawLat, rawLon)
                    val dest = MessageValidator.validateCoordinate(rawDLat, rawDLon)
                    val newLat = loc?.first
                    val newLon = loc?.second
                    val newDLat = dest?.first
                    val newDLon = dest?.second

                    val type = json.optString("type", "text")
                    // optString returns "" if missing, so we check explicit null or empty
                    val incomingPubKey = json.optString("p", "").takeIf { it.isNotEmpty() }

                    // Route DHT messages (validated inside DhtManager)
                    if (type == "dht_store") {
                        dhtManager.handleDhtMessage(json)
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

                    // Reject unknown message types
                    if (!MessageValidator.isKnownMessageType(type)) {
                        Log.w("PeersViewModel", "Rejected unknown message type: $type")
                        return
                    }
                    
                    if (type == "status") messageText = ""

                    // Validate sender onion address
                    if (!MessageValidator.isValidOnionAddress(senderOnion)) {
                        Log.w("PeersViewModel", "Rejected message with invalid onion address")
                        return
                    }

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
                }
            }
        } catch (e: Exception) {
            Log.e("PeersViewModel", "Error parsing incoming msg", e)
        }
    }

    // ========== PEER & MESSAGE OPERATIONS ==========

    fun getMessagesForPeer(peerPk: String): Flow<List<ChatMessage>> {
        return repository.getMessagesForPeer(peerPk)
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
                
                val currentSocksPort = torManager.socksPort.value ?: 9050
                repository.sendMessage(peer.lastKnownIp, payload, currentSocksPort)
                
                repository.updateMessage(initialMsg.copy(id = msgId, status = "DELIVERED"))
                _debugLogs.add(0, "OUTBOUND to ${peer.nickname}: DELIVERED")
            } catch (e: Exception) {
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
                
                mySeats = (mySeats - 1).coerceAtLeast(0)
                _debugLogs.add(0, "Ride accepted. Seats remaining: $mySeats")
                
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
        savePeer(
            lastKnownIp = cleanOnion, 
            nickname = localNickname, 
            publicKey = cleanOnion,
            onion = cleanOnion, 
            role = "NONE", 
            seats = 0, 
            info = "Manual Add"
        )
        
        viewModelScope.launch {
            try {
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

            val currentPeers = storedPeers.first()
            
            currentPeers.forEach { peer ->
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
                    if (dhtManager.kademliaNode != null) {
                        routeGrids.forEach { gridId ->
                            dhtManager.sendToGrid(dhtContent, myNickname, gridId, commonTimestamp, routeManager, mySeats)
                        }
                        _debugLogs.add(0, "DHT: Route broadcast to ${routeGrids.size} grids")
                    }
                }
            }
        }
    }

    // ========== ROUTE MANAGER DELEGATES ==========

    fun updateLocation(lat: Double, lon: Double) {
        routeManager.updateLocation(lat, lon)
    }

    fun clearAll() {
        routeManager.clearAll()
        isBroadcasting = false
    }

    fun clearWaypoint() {
        routeManager.clearWaypoint(myRole)
    }

    fun calculateRoute() {
        routeManager.calculateRoute()
    }

    // ========== DHT MANAGER DELEGATES ==========

    fun addDebugOffer() {
        dhtManager.addDebugOffer(myLatitude, myLongitude)
    }

    fun getFilteredOffers(): List<KademliaNode.GridMessage> {
        return dhtManager.getFilteredOffers(myRole, myGridId, myDestGridId, maxWalkingDistanceMeters)
    }

    fun calculateWalkDistances(offer: KademliaNode.GridMessage): Pair<Int, Int> {
        return dhtManager.calculateWalkDistances(offer, myLatitude, myLongitude, myDestLat, myDestLon)
    }

    // ========== VOUCH & UPDATE HANDLING ==========

    private suspend fun handleVouchMessage(json: org.json.JSONObject) {
        val targetPk = json.optString("target", "")
        val voucherNick = MessageValidator.sanitizeString(json.optString("v_name", "Unknown"), MessageValidator.MAX_NICKNAME_LENGTH)
        val sig = json.optString("sig", "")
        val timestamp = json.optLong("t", 0)
        val voucherOnion = json.optString("f_onion", "")
        val voucherFullPk = json.optString("p", "")

        if (targetPk.isEmpty() || sig.isEmpty() || voucherOnion.isEmpty() || voucherFullPk.isEmpty()) return

        // Validate onion address and timestamp
        if (!MessageValidator.isValidOnionAddress(voucherOnion)) {
            Log.w("PeersViewModel", "Vouch rejected: invalid voucher onion")
            return
        }
        if (!MessageValidator.isValidTimestamp(timestamp)) {
            Log.w("PeersViewModel", "Vouch rejected: invalid timestamp")
            return
        }

        val dataToVerify = "$targetPk|$timestamp"
        val pubKeyBytes = android.util.Base64.decode(voucherFullPk, android.util.Base64.NO_WRAP)
        
        val isValid = CryptoUtils.verifySignature(dataToVerify, sig, pubKeyBytes)
        
        if (isValid) {
            _debugLogs.add(0, "WoT: Trusted Vouch from $voucherNick for $targetPk")
            repository.saveVouch(VouchEntity(voucherOnion, targetPk, sig, timestamp))
            
            val peers = storedPeers.first()
            val targetPeer = peers.find { it.publicKey == targetPk || it.onion == targetPk }
            targetPeer?.let { 
                savePeer(it.lastKnownIp, it.nickname, it.publicKey, it.onion, it.role, it.seats, it.extraInfo, it.latitude, it.longitude, it.destLat, it.destLon, it.fullPublicKey)
            }
        } else {
            _debugLogs.add(0, "WoT: REJECTED invalid Vouch from $voucherNick")
        }
    }

    fun vouchForPeer(peer: Peer) {
        viewModelScope.launch {
            val timestamp = System.currentTimeMillis()
            val dataToSign = "${peer.publicKey}|$timestamp"
            val signature = CryptoUtils.signData(dataToSign) ?: return@launch
            
            val vouchJson = org.json.JSONObject().apply {
                put("v", 2)
                put("type", "vouch")
                put("target", peer.publicKey)
                put("v_name", "Me")
                put("sig", signature)
                put("t", timestamp)
                put("f_onion", torOnionAddress.value ?: "")
                put("p", myFullPubKey ?: "")
            }.toString()

            _debugLogs.add(0, "WoT: Vouching for ${peer.nickname}...")
            
            repository.saveVouch(VouchEntity(torOnionAddress.value ?: "me", peer.publicKey, signature, timestamp))
            
            savePeer(peer.lastKnownIp, peer.nickname, peer.publicKey, peer.onion, peer.role, peer.seats, peer.extraInfo, peer.latitude, peer.longitude, peer.destLat, peer.destLon, peer.fullPublicKey)

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
        
        // Validate URL format
        if (!MessageValidator.isValidUpdateUrl(url)) {
            Log.w("PeersViewModel", "Update rejected: invalid URL format")
            return
        }

        val currentVersion = updateManager.currentVersionCode

        if (version > 0 && url.isNotEmpty() && sig.isNotEmpty() && version > currentVersion) {
            _debugLogs.add(0, "UPDATE: v$version available (current: v$currentVersion). Downloading...")
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

    // ========== LIFECYCLE ==========

    override fun onCleared() {
        super.onCleared()
        repository.stopServer()
        torManager.stopTor()
    }
}

class PeersViewModelFactory(
    private val repository: PeerRepository, 
    private val torManager: SamizdatTorManager,
    private val updateManager: UpdateManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PeersViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PeersViewModel(repository, torManager, updateManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
