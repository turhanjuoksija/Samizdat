package com.example.samizdat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class PeerRepository(
    private val peerDao: PeerDao,
    private val messageDao: MessageDao,
    private val vouchDao: VouchDao,
    private val rideIntentDao: RideIntentDao,
    private val connectionManager: ConnectionManager
) {
    val storedPeers: Flow<List<Peer>> = peerDao.getAllPeers()
    val allVouches: Flow<List<VouchEntity>> = vouchDao.getAllVouches()

    suspend fun saveVouch(vouch: VouchEntity) {
        vouchDao.insertVouch(vouch)
    }

    suspend fun getVouchCount(targetPk: String): Int {
        return vouchDao.getVouchCount(targetPk)
    }

    val incomingMessages = connectionManager.incomingMessages

    suspend fun savePeer(peer: Peer) {
        peerDao.insertPeer(peer)
    }

    suspend fun deletePeer(peer: Peer) {
        peerDao.deletePeer(peer)
    }

    suspend fun getPeerByOnion(onion: String): Peer? {
        return peerDao.getPeerByOnion(onion)
    }

    suspend fun sendMessage(ip: String, message: String, socksPort: Int) {
        // Using fixed port for now
        connectionManager.sendMessage(ip, 12345, message, socksPort)
    }

    fun getMessagesForPeer(peerPk: String): Flow<List<ChatMessage>> {
        return messageDao.getMessagesForPeer(peerPk)
    }

    val incomingRequestsForUs: Flow<List<ChatMessage>> = messageDao.getRequestsForUs()
    val recentConversations: Flow<List<ChatMessage>> = messageDao.getRecentConversations()

    suspend fun saveMessage(message: ChatMessage): Long {
        return messageDao.insertMessage(message)
    }

    suspend fun updateMessage(message: ChatMessage) {
        messageDao.updateMessage(message)
    }

    // Ride Intent Operations
    // private val rideIntentDao = ... (injected)

    val activeRideIntents: Flow<List<RideIntent>> = rideIntentDao.getAllActiveIntents()

    suspend fun getActiveIntentCount(): Int {
        return rideIntentDao.getActiveIntentCount()
    }

    suspend fun saveRideIntent(intent: RideIntent): Long {
        return rideIntentDao.insertIntent(intent)
    }

    suspend fun deleteRideIntent(intent: RideIntent) {
        rideIntentDao.deleteIntent(intent)
    }

    suspend fun expireOldIntents() {
        rideIntentDao.expireOldIntents(System.currentTimeMillis())
    }

    // Start listening for incoming messages
    fun startServer(scope: CoroutineScope) {
        scope.launch {
            connectionManager.startListening(12345)
        }
    }
    
    fun stopServer() {
        connectionManager.stop()
    }
}
