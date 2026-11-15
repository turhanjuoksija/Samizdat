package com.example.samizdat

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.util.Log
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val updateManager: UpdateManager,
    val segmentManager: SegmentManager,
    val apkSeeder: ApkSeeder
) : ViewModel() {
    
    // ========== MANAGERS ==========
    
    /**
     * Route Manager - handles all routing, GPS, and waypoint logic.
     * Engine order: BRouter (offline, private) -> OSRM (online, temporary fallback) -> straight line.
     */
    val routeManager = RouteManager(
        scope = viewModelScope,
        engines = listOf(BRouterEngine(segmentManager), OsrmEngine),
        onDebugLog = { msg -> _debugLogs.add(0, msg) }
    )
    
    /** DHT Manager - handles Kademlia/DHT operations */
    val dhtManager = DhtManager(
        scope = viewModelScope,
        onDebugLog = { msg -> _debugLogs.add(0, msg) },
        getTorOnionAddress = { torManager.onionAddress.value },
        getTorSocksPort = { torManager.socksPort.value },
        sendMessageToPeer = { target, message, socksPort ->
            repository.sendMessage(target, message, socksPort)
            markPeerAlive(target)
        }
    )

    // ========== LIVE PEERS TRACKING ==========
    companion object {
        const val LIVE_PEER_WINDOW_MS = 10 * 60 * 1000L // 10 minutes
        private const val STATUS_REPLY_COOLDOWN_MS = 60 * 1000L // 1 minute
        private const val UPDATE_GOSSIP_COOLDOWN_MS = 60 * 60 * 1000L // 1 hour per peer
        private const val MAX_UPDATE_SOURCES = 20
    }

    val livePeerTimestamps = androidx.compose.runtime.mutableStateMapOf<String, Long>()
    private val lastStatusReplyTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun markPeerAlive(onionOrIp: String?) {
        if (onionOrIp.isNullOrBlank()) return
        val clean = OnionUtils.ensureOnionSuffix(onionOrIp)
        if (!OnionUtils.isOnion(clean)) return
        if (clean == torManager.onionAddress.value) return
        livePeerTimestamps[clean] = System.currentTimeMillis()
        dhtManager.kademliaNode?.addPeer(clean)
    }

    fun isPeerLive(onionOrIp: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (onionOrIp.isNullOrBlank()) return false
        val clean = OnionUtils.ensureOnionSuffix(onionOrIp)
        val lastHeard = livePeerTimestamps[clean] ?: return false
        return (nowMs - lastHeard) <= LIVE_PEER_WINDOW_MS
    }

    fun getLivePeerCount(nowMs: Long = System.currentTimeMillis()): Int {
        val myOnion = torManager.onionAddress.value
        return livePeerTimestamps.count { (onion, ts) ->
            onion != myOnion && (nowMs - ts) <= LIVE_PEER_WINDOW_MS
        }
    }
    
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
                activeRidePeers.clear()
            }
        }
    
    var mySeats by mutableIntStateOf(3)
    var myInfo by mutableStateOf("")
    var myFullPubKey by mutableStateOf<String?>(null)
    var myNickname by mutableStateOf("Me")
    var isBroadcasting by mutableStateOf(false)
    var isSetLocationMode by mutableStateOf(false)
    var isWaypointMode by mutableStateOf(false)
    val activeRidePeers = mutableStateListOf<String>()
    
    // ========== PASSENGER PICKUP FLOW STATE ==========
    var reviewingOffer by mutableStateOf<KademliaNode.GridMessage?>(null)
    var reviewingPickupIndex by mutableIntStateOf(-1)
    
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
    val routeIsApproximate: Boolean get() = routeManager.routeIsApproximate
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
    // Dismissed, accepted & mapped request tracking
    private val _dismissedRequestIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _acceptedRequestIds = MutableStateFlow<Set<Long>>(emptySet())
    private val _mappedRequestIds = MutableStateFlow<Set<Long>>(emptySet()) // Requests user has clicked "Map" on

    val incomingRequests: Flow<List<ChatMessage>> = combine(
        repository.incomingRequestsForUs,
        _dismissedRequestIds
    ) { requests, dismissed ->
        requests.filter { msg -> msg.id !in dismissed }
            .sortedByDescending { it.timestamp }
            .distinctBy { it.peerPublicKey } // One request per onion
    }

    // Letter assignments for linking list cards to map markers (A, B, C...)
    private val _requestLetters = MutableStateFlow<Map<String, String>>(emptyMap())

    fun updateRequestLetters(requests: List<ChatMessage>) {
        val newMap = mutableMapOf<String, String>()
        requests.forEachIndexed { index, msg ->
            val letter = ('A' + index).toString()
            newMap[msg.peerPublicKey] = letter
        }
        _requestLetters.value = newMap
    }

    fun getRequestLetter(publicKey: String): String? = _requestLetters.value[publicKey]
    
    fun dismissRequest(msg: ChatMessage) {
        _dismissedRequestIds.value = _dismissedRequestIds.value + msg.id
    }

    fun isRequestAccepted(msgId: Long): Boolean = msgId in _acceptedRequestIds.value
    fun isRequestMapped(msgId: Long): Boolean = msgId in _mappedRequestIds.value

    fun toggleRequestMapped(msgId: Long) {
        val current = _mappedRequestIds.value
        if (msgId in current) {
            _mappedRequestIds.value = current - msgId
        } else {
            _mappedRequestIds.value = current + msgId
        }
    }
    
    val recentConversations = repository.recentConversations
    
    // ========== TOR STATE ==========
    
    val torStatus = torManager.statusMessage
    val torOnionAddress = torManager.onionAddress
    val isTorBootstrapped = torManager.isBootstrapped

    // ========== APP UPDATE (P2P, signed manifests - DEVELOPMENT_NOTES §20) ==========

    sealed class UpdateUiState {
        object Idle : UpdateUiState()
        data class Downloading(val bytesDone: Long, val bytesTotal: Long) : UpdateUiState()
        data class ReadyToInstall(val manifest: UpdateManifest, val filePath: String) : UpdateUiState()
        data class Failed(val message: String) : UpdateUiState()
    }

    /** Newer release whose developer signatures have been verified (not yet downloaded). */
    private val _availableUpdate = MutableStateFlow<UpdateManifest?>(null)
    val availableUpdate: StateFlow<UpdateManifest?> = _availableUpdate.asStateFlow()

    private val _updateUiState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateUiState: StateFlow<UpdateUiState> = _updateUiState.asStateFlow()

    /** Version for which the user tapped "Later" in the update dialogs. */
    var updateDialogDismissedVersion by mutableIntStateOf(0)
    var installDialogDismissedVersion by mutableIntStateOf(0)

    /** Known download sources per version (unsigned hints; content is pinned by the signed hash). */
    private val updateSources = java.util.concurrent.ConcurrentHashMap<Int, MutableSet<String>>()
    private val _updateSourceCount = MutableStateFlow(0)
    val updateSourceCount: StateFlow<Int> = _updateSourceCount.asStateFlow()

    /** Last time we sent an update announcement to a peer (max 1 per peer per hour). */
    private val lastUpdateGossip = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var updateDownloadJob: kotlinx.coroutines.Job? = null

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

        // Restore the last verified release manifest: seed it if it is our installed version,
        // or offer it again if it is newer. Re-verified because SharedPreferences are not a trust anchor.
        viewModelScope.launch {
            val stored = updateManager.loadStoredManifest() ?: return@launch
            if (stored.version < updateManager.currentVersionCode) return@launch
            val valid = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                stored.verify(updateManager.packageName)
            }
            if (valid) acceptVerifiedManifest(stored)
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
        dhtManager.savePeerInfo = { onion, nick, pubKey ->
            markPeerAlive(onion)
            savePeer(
                lastKnownIp = onion,
                nickname = nick,
                publicKey = onion,
                onion = onion,
                fullPk = pubKey
            )
        }
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
                    
                    // Ping existing stored peers and run Nostr discovery so new phones are always found
                    pingAllPeers()
                    _debugLogs.add(0, "Starting Nostr Bootstrap discovery...")
                    torManager.bootstrapManager.startDiscovery(onion) { discoveredOnions ->
                        viewModelScope.launch {
                            val knownOnions = storedPeers.first().mapNotNull { it.onion }.toSet()
                            discoveredOnions.forEach { discoveredOnion ->
                                val clean = OnionUtils.ensureOnionSuffix(discoveredOnion)
                                if (clean !in knownOnions) {
                                    _debugLogs.add(0, "Nostr discovered peer: $clean")
                                    addManualPeer(clean, "Nostr Peer")
                                }
                            }
                        }
                    }

                    // Always check for App Updates via Nostr in the background (manifests are verified in handleUpdateMessage)
                    torManager.bootstrapManager.checkForUpdates { manifestJson ->
                        viewModelScope.launch { handleUpdateMessage(manifestJson) }
                    }
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

        // Live Location / High-Frequency Tracking Loop (Accepted Rides)
        viewModelScope.launch {
            while (true) {
                if (activeRidePeers.isNotEmpty() && myLatitude != null && myLongitude != null) {
                    val payload = org.json.JSONObject().apply {
                        put("v", 1)
                        put("f_onion", torManager.onionAddress.value ?: "unknown")
                        put("f_nick", myNickname)
                        put("lat", myLatitude)
                        put("lon", myLongitude)
                        put("t", System.currentTimeMillis())
                        put("type", "live_location")
                    }.toString()
                    
                    val currentPeers = storedPeers.first()
                    activeRidePeers.forEach { peerPk ->
                        val targetPeer = currentPeers.find { it.publicKey == peerPk }
                        if (targetPeer != null && !targetPeer.onion.isNullOrEmpty()) {
                            launch {
                                val currentSocksPort = torManager.socksPort.value ?: return@launch
                                try {
                                    repository.sendMessage(targetPeer.onion, payload, currentSocksPort)
                                } catch (e: Exception) {
                                    // ignore fast-loop errors
                                }
                            }
                        }
                    }
                }
                delay(5000) // every 5 seconds
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
                    // "p" carries the RSA public key ONLY in "status" messages; in chat/ride messages "p" is message text!
                    val incomingPubKey = if (type == "status") {
                        json.optString("p", "").takeIf { it.isNotEmpty() }
                    } else null

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
                        // Verify off the message-collector path (may hash the installed APK when seeding)
                        viewModelScope.launch { handleUpdateMessage(json) }
                        return
                    }

                    // Reject unknown message types
                    if (!MessageValidator.isKnownMessageType(type) && type != "live_location") {
                        Log.w("PeersViewModel", "Rejected unknown message type: $type")
                        return
                    }
                    
                    if (type == "status" || type == "live_location") messageText = ""

                    // Validate sender onion address
                    if (!MessageValidator.isValidOnionAddress(senderOnion)) {
                        Log.w("PeersViewModel", "Rejected message with invalid onion address")
                        return
                    }

                    val lookupId = OnionUtils.ensureOnionSuffix(senderOnion)
                    markPeerAlive(lookupId)
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

                    // Announce a newer signed release to peers running an older version (rate limited)
                    if (type == "status") {
                        val peerAppVersion = json.optInt("app_v", 0)
                        val targetOnion = resolvedPeer?.onion ?: if (OnionUtils.isOnion(lookupId)) lookupId else null
                        if (targetOnion != null) {
                            maybeGossipUpdate(targetOnion, peerAppVersion, senderNick)
                        }

                        // Reply with our own status (at most once per minute per peer) so single-sided handshakes become bidirectional
                        val now = System.currentTimeMillis()
                        val lastReply = lastStatusReplyTimestamps[lookupId] ?: 0L
                        if (now - lastReply > STATUS_REPLY_COOLDOWN_MS && lookupId != torManager.onionAddress.value) {
                            lastStatusReplyTimestamps[lookupId] = now
                            viewModelScope.launch {
                                val currentSocksPort = torManager.socksPort.value ?: return@launch
                                try {
                                    repository.sendMessage(lookupId, getMyStatusPayload(), currentSocksPort)
                                    markPeerAlive(lookupId)
                                } catch (_: Exception) { }
                            }
                        }
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
                        
                        // Auto-start tracking if accepted
                        if (type == "ride_accept" && !activeRidePeers.contains(finalPk)) {
                            activeRidePeers.add(finalPk)
                            _debugLogs.add(0, "Started live tracking with ${senderNick ?: finalPk}")
                        }
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
                
                val currentSocksPort = torManager.socksPort.value
                if (currentSocksPort == null) {
                    repository.updateMessage(initialMsg.copy(id = msgId, status = "FAILED"))
                    _debugLogs.add(0, "ERROR: Tor SOCKS port not available")
                    return@launch
                }
                repository.sendMessage(peer.lastKnownIp, payload, currentSocksPort)
                markPeerAlive(peer.onion ?: peer.lastKnownIp)
                
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
                acceptRide(peer)
                _acceptedRequestIds.value = _acceptedRequestIds.value + requestMsg.id
            } else {
                _debugLogs.add(0, "ERROR: Peer not found for request")
            }
        }
    }

    fun acceptRide(peer: Peer) {
        viewModelScope.launch {
            sendMessage(
                peer = peer,
                message = "Ride accepted! 🚕 I'm on my way.",
                myNickname = myNickname,
                type = "ride_accept"
            )
            
            if (!activeRidePeers.contains(peer.publicKey)) {
                activeRidePeers.add(peer.publicKey)
            }
            
            mySeats = (mySeats - 1).coerceAtLeast(0)
            _debugLogs.add(0, "Ride accepted for ${peer.nickname}. Seats remaining: $mySeats")
            
            if (mySeats == 0 && isBroadcasting) {
                isBroadcasting = false
                _debugLogs.add(0, "BROADCAST STOPPED: No more seats available")
            }
        }
    }

    fun declineRideRequest(requestMsg: ChatMessage) {
         viewModelScope.launch {
            val peer = storedPeers.first().find { it.publicKey == requestMsg.peerPublicKey }
            if (peer != null) {
                declineRide(peer)
            }
        }
    }

    fun declineRide(peer: Peer) {
        viewModelScope.launch {
             sendMessage(
                peer = peer,
                message = "Unfortunately, I can't make it now. 🚫",
                myNickname = myNickname,
                type = "ride_decline"
            )
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
                val currentSocksPort = torManager.socksPort.value ?: return@launch
                lastStatusReplyTimestamps[cleanOnion] = System.currentTimeMillis()
                repository.sendMessage(cleanOnion, payload, currentSocksPort)
                markPeerAlive(cleanOnion)
                _debugLogs.add(0, "HANDSHAKE: Sent Hello to $localNickname")
            } catch (e: Exception) {
                _debugLogs.add(0, "HANDSHAKE FAILED: ${e.message}")
            }
        }
    }

    fun pingAllPeers() {
        viewModelScope.launch {
            val currentSocksPort = torManager.socksPort.value ?: run {
                _debugLogs.add(0, "PING: Waiting for Tor SOCKS port...")
                return@launch
            }
            val myOnion = torManager.onionAddress.value
            val payload = getMyStatusPayload()
            val peers = storedPeers.first().filter { !it.onion.isNullOrEmpty() && it.onion != myOnion }
            if (peers.isEmpty()) {
                _debugLogs.add(0, "PING: No saved peers to ping")
                return@launch
            }
            _debugLogs.add(0, "PING: Checking ${peers.size} known peer(s)...")
            peers.forEach { peer ->
                val targetOnion = peer.onion ?: return@forEach
                launch {
                    try {
                        lastStatusReplyTimestamps[targetOnion] = System.currentTimeMillis()
                        repository.sendMessage(targetOnion, payload, currentSocksPort)
                        markPeerAlive(targetOnion)
                        _debugLogs.add(0, "PING OK: ${peer.nickname} is LIVE")
                    } catch (e: Exception) {
                        Log.d("PeersViewModel", "Ping failed for ${peer.nickname}: ${e.message}")
                    }
                }
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
            put("app_v", updateManager.currentVersionCode) // Advertise our version for gossip
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
                        val currentSocksPort = torManager.socksPort.value ?: return@launch
                        try {
                            repository.sendMessage(peer.lastKnownIp, payload, currentSocksPort)
                            markPeerAlive(peer.onion ?: peer.lastKnownIp)
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
    
    // Find the closest point index on a route to a given coordinate
    fun findClosestRouteIndex(lat: Double, lon: Double, routePoints: List<Pair<Double, Double>>): Int {
        if (routePoints.isEmpty()) return -1
        var minDistance = Float.MAX_VALUE
        var closestIndex = -1
        
        routePoints.forEachIndexed { index, point ->
            val results = FloatArray(1)
            android.location.Location.distanceBetween(lat, lon, point.first, point.second, results)
            if (results[0] < minDistance) {
                minDistance = results[0]
                closestIndex = index
            }
        }
        return closestIndex
    }

    // Estimate driving distance along a route between two coordinates
    fun calculateDistanceAlongRoute(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, routePoints: List<Pair<Double, Double>>): Float {
        if (routePoints.isEmpty()) {
             // Fallback to straight line
             val results = FloatArray(1)
             android.location.Location.distanceBetween(fromLat, fromLon, toLat, toLon, results)
             return results[0]
        }
        
        val startIndex = findClosestRouteIndex(fromLat, fromLon, routePoints)
        val endIndex = findClosestRouteIndex(toLat, toLon, routePoints)
        
        if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) {
            // Invalid, or going backwards
             val results = FloatArray(1)
             android.location.Location.distanceBetween(fromLat, fromLon, toLat, toLon, results)
             return results[0]
        }
        
        var distance = 0f
        for (i in startIndex until endIndex) {
            val p1 = routePoints[i]
            val p2 = routePoints[i+1]
            val results = FloatArray(1)
            android.location.Location.distanceBetween(p1.first, p1.second, p2.first, p2.second, results)
            distance += results[0]
        }
        return distance
    }

    fun calculateDriverDistanceToPickup(offer: KademliaNode.GridMessage): Int? {
        val pLat = myLatitude ?: return null
        val pLon = myLongitude ?: return null
        val dLat = offer.driverCurrentLat ?: return null
        val dLon = offer.driverCurrentLon ?: return null
        
        // 1. Find pickup point on route
        if (offer.routePoints.isEmpty()) {
            // Fallback to straight line if no route
            val results = FloatArray(1)
            android.location.Location.distanceBetween(dLat, dLon, pLat, pLon, results)
            return results[0].toInt()
        }
        
        val pickupIndex = findClosestRouteIndex(pLat, pLon, offer.routePoints)
        if (pickupIndex == -1) return null
        
        val pickupPoint = offer.routePoints[pickupIndex]
        
        // 2. Calculate distance along route from driver to pickup
        return calculateDistanceAlongRoute(dLat, dLon, pickupPoint.first, pickupPoint.second, offer.routePoints).toInt()
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

            val currentSocksPort = torManager.socksPort.value ?: return@launch
            storedPeers.first().forEach { p ->
                if (!p.onion.isNullOrEmpty()) {
                    try {
                        repository.sendMessage(p.onion, vouchJson, currentSocksPort)
                    } catch (e: Exception) {
                        Log.e("PeersViewModel", "Failed to send vouch to ${p.nickname}")
                    }
                }
            }
        }
    }

    /**
     * Handles an update announcement from a peer (gossip) or from Nostr.
     *
     * Lock 1: nothing is downloaded, stored or relayed unless BOTH developer signatures are valid.
     * Legacy announcements (no sha256/size/sig2) cannot be verified before download and are ignored.
     * State is only updated AFTER verification, so a forged "v999999" can no longer block real updates.
     */
    private suspend fun handleUpdateMessage(json: org.json.JSONObject) {
        val manifest = UpdateManifest.fromJson(json)
        if (manifest == null) {
            Log.w("PeersViewModel", "Ignored unsigned/legacy update announcement (v${json.optInt("ver", -1)})")
            return
        }

        // Already-verified release: just remember the extra source (no repeated RSA work)
        val known = listOfNotNull(_availableUpdate.value, apkSeeder.seedManifest.value)
            .firstOrNull { it.sameReleaseAs(manifest) }
        if (known != null) {
            addUpdateSource(known.version, manifest.url)
            return
        }

        val valid = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            manifest.verify(updateManager.packageName)
        }
        if (!valid) {
            _debugLogs.add(0, "UPDATE: REJECTED v${manifest.version} announcement (invalid developer signature)")
            return
        }
        acceptVerifiedManifest(manifest)
    }

    /** Called only with signature-verified manifests. */
    private suspend fun acceptVerifiedManifest(manifest: UpdateManifest) {
        val current = updateManager.currentVersionCode
        addUpdateSource(manifest.version, manifest.url)
        when {
            manifest.version == current -> {
                // Our installed release: start seeding if the installed APK is byte-identical
                val stored = updateManager.loadStoredManifest()
                if (stored == null || stored.version <= manifest.version) updateManager.storeManifest(manifest)
                if (apkSeeder.offerSelf(manifest)) {
                    _debugLogs.add(0, "UPDATE: Sharing own v${manifest.version} with peers (signed release verified)")
                } else {
                    _debugLogs.add(0, "UPDATE: Not sharing v${manifest.version}: ${apkSeeder.status.value}")
                }
            }
            manifest.version > current -> {
                val existing = _availableUpdate.value
                if (existing == null || manifest.version > existing.version) {
                    _availableUpdate.value = manifest
                    updateManager.storeManifest(manifest)
                    _updateSourceCount.value = updateSources[manifest.version]?.size ?: 0
                    _debugLogs.add(0, "UPDATE: v${manifest.version} available (developer signature verified ✅)")
                }
            }
            else -> { /* older than installed: ignore (downgrade protection) */ }
        }
    }

    private fun addUpdateSource(version: Int, url: String?) {
        if (url.isNullOrBlank() || !MessageValidator.isValidUpdateUrl(url)) return
        // Never use ourselves as a download source
        val myOnion = torManager.onionAddress.value
        if (myOnion != null && url.contains(myOnion)) return
        val set = updateSources.getOrPut(version) { java.util.Collections.synchronizedSet(LinkedHashSet()) }
        if (set.size < MAX_UPDATE_SOURCES || url in set) set.add(url)
        if (_availableUpdate.value?.version == version) _updateSourceCount.value = set.size
    }

    /**
     * Sends our best known signed release to a peer that reports an older version.
     * Max once per peer per hour. If we seed the release ourselves, our own onion is the source.
     * The message stays readable by older app versions (ver/url/sig).
     */
    private fun maybeGossipUpdate(peerOnion: String, peerAppVersion: Int, peerNick: String?) {
        if (peerAppVersion <= 0) return
        val myOnion = torManager.onionAddress.value ?: return
        if (peerOnion == myOnion) return

        val seed = apkSeeder.seedManifest.value
        val available = _availableUpdate.value
        val (manifest, sourceUrl) = when {
            available != null && (seed == null || available.version > seed.version) ->
                available to updateSources[available.version]?.toList()?.randomOrNull()
            seed != null -> seed to UpdateManifest.sourceUrlFor(myOnion, seed.version)
            else -> return
        }
        if (sourceUrl == null || peerAppVersion >= manifest.version) return

        val now = System.currentTimeMillis()
        val last = lastUpdateGossip[peerOnion] ?: 0L
        if (now - last < UPDATE_GOSSIP_COOLDOWN_MS) return
        lastUpdateGossip[peerOnion] = now

        viewModelScope.launch {
            val socksPort = torManager.socksPort.value ?: return@launch
            try {
                repository.sendMessage(peerOnion, manifest.toGossipJson(sourceUrl), socksPort)
                markPeerAlive(peerOnion)
                _debugLogs.add(0, "GOSSIP: Announced signed v${manifest.version} to ${peerNick ?: peerOnion.take(12)} (they have v$peerAppVersion)")
            } catch (_: Exception) {
                lastUpdateGossip.remove(peerOnion)
            }
        }
    }

    /** User accepted: download from peers (one source at a time) and verify (size, SHA-256, certificate). */
    fun downloadAvailableUpdate() {
        val manifest = _availableUpdate.value ?: return
        if (updateDownloadJob?.isActive == true) return
        val sources = updateSources[manifest.version]?.toList()?.shuffled().orEmpty()
        if (sources.isEmpty()) {
            _updateUiState.value = UpdateUiState.Failed("No peer is sharing v${manifest.version} yet. Try again later.")
            return
        }
        updateDownloadJob = viewModelScope.launch {
            _updateUiState.value = UpdateUiState.Downloading(0, manifest.size)
            _debugLogs.add(0, "UPDATE: Downloading v${manifest.version} over Tor (${sources.size} source(s))...")
            try {
                val file = updateManager.downloadVerified(manifest, sources) { done, total ->
                    _updateUiState.value = UpdateUiState.Downloading(done, total)
                }
                _updateUiState.value = UpdateUiState.ReadyToInstall(manifest, file.absolutePath)
                _debugLogs.add(0, "UPDATE: v${manifest.version} verified ✅ (signature, SHA-256, certificate). Ready to install.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                _updateUiState.value = UpdateUiState.Idle
                throw e
            } catch (e: Exception) {
                _updateUiState.value = UpdateUiState.Failed(e.message ?: "Download failed")
                _debugLogs.add(0, "UPDATE ERROR: ${e.message}")
            }
        }
    }

    fun cancelUpdateDownload() {
        updateDownloadJob?.cancel()
        _updateUiState.value = UpdateUiState.Idle
    }

    fun installDownloadedUpdate() {
        val state = _updateUiState.value as? UpdateUiState.ReadyToInstall ?: return
        updateManager.installApk(java.io.File(state.filePath))
    }

    // ========== OFFLINE ROUTING DATA ==========

    private var segmentDownloadJob: kotlinx.coroutines.Job? = null

    /** Segments the active route needs for fully offline routing (empty if no route). */
    fun routeRequiredSegments(): List<String> = routeManager.requiredSegments()

    /**
     * Downloads the BRouter segment covering the user's current position over Tor,
     * then recalculates the route so it is computed offline.
     */
    fun downloadOfflineRegion() {
        val lat = routeManager.myLatitude
        val lon = routeManager.myLongitude
        if (lat == null || lon == null) {
            _debugLogs.add(0, "OFFLINE DATA: No location yet - cannot pick a region")
            return
        }
        downloadSegments(listOf(SegmentManager.segmentName(lat, lon)))
    }

    /** Downloads every segment the active route needs but that is not installed yet. */
    fun downloadRouteSegments() {
        val missing = routeManager.requiredSegments().filterNot { segmentManager.isInstalled(it) }
        if (missing.isEmpty()) {
            _debugLogs.add(0, "OFFLINE DATA: Route already fully covered by offline data")
            return
        }
        downloadSegments(missing)
    }

    /**
     * Downloads the given segments over Tor (one job at a time) and recalculates the route
     * afterwards so newly covered legs switch from OSRM to offline BRouter.
     */
    fun downloadSegments(names: List<String>) {
        if (segmentDownloadJob?.isActive == true) return
        val label = names.joinToString(", ")
        segmentManager.clearDownloadState()
        segmentDownloadJob = viewModelScope.launch {
            _debugLogs.add(0, "OFFLINE DATA: Downloading $label over Tor...")
            segmentManager.download(names)
            val state = segmentManager.downloadState.value
            _debugLogs.add(0, "OFFLINE DATA: $label -> ${if (state is SegmentManager.DownloadState.Error) "FAILED: ${state.message}" else "OK"}")
            if (state is SegmentManager.DownloadState.Done) routeManager.calculateRoute()
        }
    }

    fun cancelSegmentDownload() {
        if (segmentDownloadJob?.isActive == true) {
            segmentDownloadJob?.cancel()
            segmentManager.cancelActiveDownload()
            _debugLogs.add(0, "OFFLINE DATA: Download cancelled")
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
    private val updateManager: UpdateManager,
    private val segmentManager: SegmentManager,
    private val apkSeeder: ApkSeeder
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PeersViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PeersViewModel(repository, torManager, updateManager, segmentManager, apkSeeder) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
