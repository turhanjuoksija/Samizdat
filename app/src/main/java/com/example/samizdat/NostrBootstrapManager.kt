package com.example.samizdat

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Manages the Bootstrap process via the Nostr Network.
 */
class NostrBootstrapManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var activeWebSocket: WebSocket? = null
    
    // We can expand this list in the future or let users add custom relays
    private val defaultRelays = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band"
    )

    // A map to store discovered Onion addresses and their timestamp to prefer newer ones
    private val discoveredOnions = mutableMapOf<String, Long>()
    
    // Our own onion address, used to filter it out during discovery
    private var myOwnOnion: String? = null

    private val prefs: SharedPreferences = context.getSharedPreferences("nostr_bootstrap", Context.MODE_PRIVATE)

    /**
     * Checks if we need to publish. Returns true if:
     * - First time ever publishing, OR
     * - 24+ hours since last publish, OR
     * - The onion address has changed since last publish (e.g., fresh install)
     */
    private fun shouldPublish(currentOnion: String): Boolean {
        val lastPublish = prefs.getLong(KEY_LAST_PUBLISH, 0L)
        val lastOnion = prefs.getString(KEY_LAST_ONION, null)
        val elapsed = System.currentTimeMillis() - lastPublish
        
        if (lastOnion != currentOnion) {
            Log.i(TAG, "Onion address changed! Must re-publish.")
            return true
        }
        return elapsed >= PUBLISH_INTERVAL_MS
    }

    private fun markPublished(onion: String) {
        prefs.edit()
            .putLong(KEY_LAST_PUBLISH, System.currentTimeMillis())
            .putString(KEY_LAST_ONION, onion)
            .apply()
    }

    /**
     * Publishes our onion address to the Nostr network with Proof of Work.
     * Only publishes once per day (24h) to avoid unnecessary mining.
     */
    fun publishOurOnion(myOnionAddress: String, onStatusUpdate: (String) -> Unit = {}) {
        myOwnOnion = myOnionAddress

        if (!shouldPublish(myOnionAddress)) {
            Log.i(TAG, "Skipping Nostr publish — last publish was less than 24h ago.")
            onStatusUpdate("Nostr: Already published recently, skipping.")
            return
        }

        scope.launch {
            try {
                Log.i(TAG, "Starting to publish our Onion to Nostr: $myOnionAddress")
                onStatusUpdate("Nostr: Starting Bootstrapper...")
                
                // 1. Generate a temporary, throw-away keypair
                val (privHex, pubHex) = NostrCryptoUtils.generateKeyPair()

                // 2. NIP-40: Expiration 7 days from now
                val nowSec = System.currentTimeMillis() / 1000
                val expirationSec = nowSec + EXPIRATION_SECONDS

                // 3. Create the NIP-01 Event with expiration tag
                val event = NostrEvent(
                    pubkey = pubHex,
                    created_at = nowSec,
                    kind = BOOTSTRAP_KIND,
                    tags = listOf(
                        listOf("samizdat_onion", myOnionAddress),
                        listOf("expiration", expirationSec.toString()) // NIP-40
                    ),
                    content = "Samizdat Bootstrap Node",
                    sig = ""
                )

                // 4. Mine the event (Proof of Work — difficulty 20, takes longer)
                val minedEvent = NostrPoWMiner.mineEvent(event, TARGET_DIFFICULTY, privHex) { progress ->
                    onStatusUpdate("Nostr: $progress")
                }

                onStatusUpdate("Nostr: Broadcasting to Relays...")

                // 5. Connect to relays and broadcast
                val eventJson = minedEvent.toJson()
                val broadcastMessage = JSONArray().apply {
                    put("EVENT")
                    put(eventJson)
                }.toString()

                broadcastToRelays(broadcastMessage)
                
                // Mark successful publish so we don't repeat within 24h
                markPublished(myOnionAddress)
                onStatusUpdate("Nostr: Published successfully!")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to publish onion to Nostr", e)
                onStatusUpdate("Nostr Init Error: ${e.message}")
            }
        }
    }

    /**
     * Connects to Nostr relays and subscribes to bootstrap events to find peers.
     */
    fun startDiscovery(myOnion: String?, onPeersFound: (List<String>) -> Unit) {
        scope.launch {
            Log.i(TAG, "Starting Nostr discovery for peers...")
            discoveredOnions.clear()

            // Calculate cutoff time (e.g., only care about active nodes from the last 24 hours)
            val sinceTimestamp = (System.currentTimeMillis() / 1000) - (24 * 60 * 60)

            // Create NIP-01 Subscription filter
            val filter = JSONObject().apply {
                put("kinds", JSONArray().put(BOOTSTRAP_KIND))
                put("since", sinceTimestamp)
                put("limit", 50) // Don't overwhelm the phone
            }

            val subId = "samizdat_bootstrap_${System.currentTimeMillis()}"
            val subscribeMessage = JSONArray().apply {
                put("REQ")
                put(subId)
                put(filter)
            }.toString()

            // Try to connect to at least one working relay
            for (relayUrl in defaultRelays) {
                var connected = connectAndSubscribe(relayUrl, subscribeMessage) { onion, timestamp ->
                    discoveredOnions[onion] = timestamp
                }
                
                if (connected) {
                    // Give it some time to receive the initial dump of events
                    delay(3000)
                    
                    // Sort by newest first & filter out our own address
                    val sortedOnions = discoveredOnions.entries
                        .filter { it.key != myOnion && it.key != myOwnOnion }
                        .sortedByDescending { it.value }
                        .map { it.key }
                    
                    if (sortedOnions.isNotEmpty()) {
                        Log.i(TAG, "Discovered ${sortedOnions.size} onions from Nostr!")
                        onPeersFound(sortedOnions)
                        // Close websocket after we got what we needed
                        activeWebSocket?.close(1000, "Done bootstrapping")
                        break // Don't need to try the next relay if this one worked
                    } else {
                        Log.i(TAG, "Relay $relayUrl returned no active peers. Trying next...")
                    }
                }
            }
        }
    }

    private fun broadcastToRelays(message: String) {
        // For publishing, we want to hit multiple relays to ensure visibility
        defaultRelays.forEach { relayUrl ->
            val request = Request.Builder().url(relayUrl).build()
            client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Connected to $relayUrl for writing")
                    webSocket.send(message)
                    // Close gracefully after sending
                    webSocket.close(1000, "Published")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Failed to publish to $relayUrl: ${t.message}")
                }
            })
        }
    }

    private suspend fun connectAndSubscribe(
        url: String, 
        subscribeMessage: String, 
        onAddressFound: (String, Long) -> Unit
    ): Boolean {
        var isConnected = false
        val request = Request.Builder().url(url).build()

        activeWebSocket?.close(1000, "Switching relays")
        
        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to $url for reading")
                isConnected = true
                webSocket.send(subscribeMessage)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONArray(text)
                    val type = message.getString(0)
                    
                    if (type == "EVENT") {
                        val eventJson = message.getJSONObject(2) // ["EVENT", "subId", {event}]
                        handleIncomingEvent(eventJson, onAddressFound)
                    } else if (type == "EOSE") {
                        // End of Stored Events
                        Log.d(TAG, "Reached EOSE for $url")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing Nostr message: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Failed to read from $url: ${t.message}")
                isConnected = false
            }
        }
        
        activeWebSocket = client.newWebSocket(request, webSocketListener)

        // Wait a tiny bit for connection establishment
        delay(1500)
        return isConnected
    }

    private fun handleIncomingEvent(eventJson: JSONObject, onAddressFound: (String, Long) -> Unit) {
        val kind = eventJson.optInt("kind", 0)
        val id = eventJson.optString("id", "")
        val createdAt = eventJson.optLong("created_at", 0)
        
        if (kind == BOOTSTRAP_KIND) {
            // VERIFY PROOF OF WORK FIRST! (Sybil protection)
            if (NostrPoWMiner.countLeadingZeroBits(id) >= TARGET_DIFFICULTY) {
                val tags = eventJson.optJSONArray("tags")
                tags?.let {
                    for (i in 0 until it.length()) {
                        val tagArray = it.optJSONArray(i)
                        // Extract our custom tag ["samizdat_onion", "the_onion_address..."]
                        if (tagArray != null && tagArray.length() >= 2 && tagArray.getString(0) == "samizdat_onion") {
                            val onion = tagArray.getString(1)
                            if (onion.endsWith(".onion")) {
                                Log.d(TAG, "Found valid bootstrap onion: $onion")
                                onAddressFound(onion, createdAt)
                            }
                        }
                    }
                }
            } else {
                Log.w(TAG, "Ignored event $id: FAILED Proof of Work check. (Potential Sybil)")
            }
        }
    }

    /**
     * Connects to Nostr relays and subscribes to app update events (Kind 10338)
     */
    fun checkForUpdates(currentVersion: Int, onUpdateFound: (Int, String, String) -> Unit) {
        scope.launch {
            Log.i(TAG, "Starting Nostr discovery for app updates...")

            // Calculate cutoff time (e.g., look for updates in the last 7 days)
            val sinceTimestamp = (System.currentTimeMillis() / 1000) - (7 * 24 * 60 * 60)

            // Create NIP-01 Subscription filter for UPDATE events
            val filter = JSONObject().apply {
                put("kinds", JSONArray().put(UPDATE_KIND))
                put("since", sinceTimestamp)
                put("limit", 10) // We only need the latest few
            }

            val subId = "samizdat_updates_${System.currentTimeMillis()}"
            val subscribeMessage = JSONArray().apply {
                put("REQ")
                put(subId)
                put(filter)
            }.toString()

            for (relayUrl in defaultRelays) {
                val connected = connectAndSubscribeToUpdates(relayUrl, subscribeMessage, currentVersion, onUpdateFound)
                if (connected) {
                    break // Connecting to one relay to fetch broadcasts is usually enough
                }
            }
        }
    }

    private suspend fun connectAndSubscribeToUpdates(
        url: String, 
        subscribeMessage: String, 
        currentVersion: Int,
        onUpdateFound: (Int, String, String) -> Unit
    ): Boolean {
        var isConnected = false
        val request = Request.Builder().url(url).build()

        val webSocketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to $url for reading updates")
                isConnected = true
                webSocket.send(subscribeMessage)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONArray(text)
                    val type = message.getString(0)
                    
                    if (type == "EVENT") {
                        val eventJson = message.getJSONObject(2) // ["EVENT", "subId", {event}]
                        handleIncomingUpdateEvent(eventJson, currentVersion, onUpdateFound, webSocket)
                    } else if (type == "EOSE") {
                        Log.d(TAG, "Reached EOSE for updates on $url")
                        // Leave the socket open briefly to ensure we process late arrivals, then we could close it.
                        // For now we'll just let it disconnect naturally or stay open.
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing Nostr update message: $text", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Failed to read updates from $url: ${t.message}")
                isConnected = false
            }
        }
        
        client.newWebSocket(request, webSocketListener)
        delay(1500)
        return isConnected
    }

    private fun handleIncomingUpdateEvent(
        eventJson: JSONObject, 
        currentVersion: Int, 
        onUpdateFound: (Int, String, String) -> Unit,
        webSocket: WebSocket
    ) {
        val kind = eventJson.optInt("kind", 0)
        if (kind != UPDATE_KIND) return

        var version = -1
        var url = ""
        var sig = ""

        val tags = eventJson.optJSONArray("tags")
        if (tags != null) {
            for (i in 0 until tags.length()) {
                val tagArray = tags.optJSONArray(i)
                if (tagArray != null && tagArray.length() >= 2) {
                    val key = tagArray.getString(0)
                    val value = tagArray.getString(1)
                    when (key) {
                        "samizdat_ver" -> version = value.toIntOrNull() ?: -1
                        "samizdat_url" -> url = value
                        "samizdat_sig" -> sig = value
                    }
                }
            }
        }

        // We only care if it's strictly newer
        if (version > currentVersion && url.isNotEmpty() && sig.isNotEmpty()) {
            Log.i(TAG, "Found App Update via Nostr! v$version at $url")
            onUpdateFound(version, url, sig)
            // Can close the socket since we found what we wanted
            webSocket.close(1000, "Update found")
        }
    }

    companion object {
        private const val TAG = "NostrBootstrap"
        // Target difficulty in bits. 22 = 5 leading hex zeros + 2 bits. Takes ~2-5 min on a phone.
        const val TARGET_DIFFICULTY = 22 
        // Replaceable event kind for app-specific discovery.
        // 10000-19999 are "Replaceable", meaning relays STORE the latest event per pubkey.
        const val BOOTSTRAP_KIND = 10337
        // Replaceable event kind for broadcasting App Updates
        const val UPDATE_KIND = 10338
        // NIP-40: Events expire after 7 days (relays should auto-delete)
        const val EXPIRATION_SECONDS = 7L * 24 * 60 * 60  // 7 days
        // Only re-publish once per 24 hours
        const val PUBLISH_INTERVAL_MS = 24L * 60 * 60 * 1000  // 24 hours
        private const val KEY_LAST_PUBLISH = "last_nostr_publish_ms"
        private const val KEY_LAST_ONION = "last_nostr_publish_onion"
    }
}
