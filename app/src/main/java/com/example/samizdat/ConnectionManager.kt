package com.example.samizdat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.Proxy
import java.net.InetSocketAddress

class ConnectionManager {
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    
    // Emissions of received messages: Pair(SenderIP, MessageContent)
    private val _incomingMessages = MutableSharedFlow<Pair<String, String>>()
    val incomingMessages: SharedFlow<Pair<String, String>> = _incomingMessages

    suspend fun startListening(port: Int) = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        try {
            // Listen on all interfaces (0.0.0.0) so Tor can connect via localhost
            serverSocket = ServerSocket(port)
            isRunning = true
            Log.i(TAG, "Server started and listening on port $port")
            
            while (isRunning) {
                try {
                    val clientSocket = serverSocket?.accept()
                    Log.i(TAG, "New connection accepted from: ${clientSocket?.inetAddress?.hostAddress}")
                    clientSocket?.let { socket ->
                        handleClient(socket)
                    }
                } catch (e: Exception) {
                    if (isRunning) Log.e(TAG, "Error accepting connection", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val writer = PrintWriter(socket.getOutputStream(), true)
            val senderIp = socket.inetAddress.hostAddress
            
            // Allow multiple lines? For now, just read one line as a message
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                line?.let { msg ->
                    Log.d(TAG, "Received from $senderIp: $msg")
                    _incomingMessages.emit((senderIp ?: "Unknown") to msg)
                    // Application-level ACK
                    writer.println("OK")
                }
            }
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        }
    }

    suspend fun sendMessage(ip: String, port: Int, message: String, socksPort: Int = 9050) = withContext(Dispatchers.IO) {
        val finalIp = OnionUtils.ensureOnionSuffix(ip)
        val isOnion = OnionUtils.isOnion(finalIp)
        val targetPort = if (isOnion) 80 else port 
        try {
            Log.d(TAG, "Connecting to $finalIp:$targetPort (isOnion: $isOnion, Proxy: $socksPort)")
            
            val socket = if (isOnion) {
                Log.i(TAG, "Using Tor SOCKS proxy at 127.0.0.1:$socksPort for $finalIp")
                // Use Tor SOCKS proxy
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
                Socket(proxy).apply {
                    connect(InetSocketAddress.createUnresolved(finalIp, targetPort), 30000) 
                }
            } else {
                Socket(finalIp, targetPort)
            }
            
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            writer.println(message)
            
            // Wait for ACK
            val response = reader.readLine()
            if (response != "OK") {
                throw Exception("No ACK received from recipient (got: $response)")
            }
            
            socket.close()
            Log.d(TAG, "Message sent and delivered to $finalIp")
        } catch (e: Exception) {
             Log.e(TAG, "Failed to send message to $finalIp:$targetPort. Error: ${e.message}", e)
             if (isOnion) {
                 Log.e(TAG, "Hint: Onion connections can take several minutes to become available after Tor initialization.")
             }
             throw e
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server", e)
        }
    }
    
    companion object {
        private const val TAG = "ConnectionManager"
    }
}
