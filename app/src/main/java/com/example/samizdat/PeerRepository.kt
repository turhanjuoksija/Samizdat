package com.example.samizdat

import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PeerRepository(
    private val peerDao: PeerDao,
    private val messageDao: MessageDao,
    private val vouchDao: VouchDao,
    private val nsdHelper: NsdHelper,
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
    
    // We can bridge discovered peers StateList to a Flow if needed, 
    // but for now ViewModel can observe NsdHelper's state directly or we pass it through.
    
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

    suspend fun sendMessage(ip: String, message: String, socksPort: Int = 9050) {
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

    fun startDiscovery() {
        nsdHelper.discoverServices()
    }

    fun stopDiscovery() {
        nsdHelper.tearDown()
    }
    
    fun registerMyService(port: Int, nickname: String, hash: String, role: String = "NONE", seats: Int = 0, info: String = "", onion: String? = null) {
        nsdHelper.registerService(port, nickname, hash, role, seats, info, onion)
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
