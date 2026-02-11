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

    // --- Input Protection ---
    companion object {
        private const val TAG = "ConnectionManager"
        private const val MAX_MESSAGE_SIZE = 65_536       // 64 KB
        private const val RATE_LIMIT_WINDOW_MS = 60_000L  // 1 minute
        private const val RATE_LIMIT_MAX_MESSAGES = 30    // per IP per window
        private const val SOCKET_TIMEOUT_MS = 60_000      // idle connection timeout
    }

    // Track message counts per IP for rate limiting
    private val rateLimitMap = java.util.concurrent.ConcurrentHashMap<String, MutableList<Long>>()
    
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
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val inputStream = socket.getInputStream()
            val writer = PrintWriter(socket.getOutputStream(), true)
            val senderIp = socket.inetAddress.hostAddress ?: "Unknown"

            // Read messages line by line with size limit
            val sb = StringBuilder()
            var ch: Int
            while (inputStream.read().also { ch = it } != -1) {
                if (ch == '\n'.code) {
                    val msg = sb.toString().trimEnd('\r')
                    sb.clear()

                    if (msg.isEmpty()) continue

                    // Rate limiting check
                    if (!checkRateLimit(senderIp)) {
                        Log.w(TAG, "Rate limit exceeded for $senderIp, dropping message")
                        writer.println("RATE_LIMITED")
                        continue
                    }

                    Log.d(TAG, "Received from $senderIp: ${msg.take(200)}${if (msg.length > 200) "..." else ""}")
                    _incomingMessages.emit(senderIp to msg)
                    writer.println("OK")
                } else {
                    sb.append(ch.toChar())
                    if (sb.length > MAX_MESSAGE_SIZE) {
                        Log.w(TAG, "Message from $senderIp exceeded $MAX_MESSAGE_SIZE bytes, dropping connection")
                        break
                    }
                }
            }
            socket.close()
        } catch (e: java.net.SocketTimeoutException) {
            Log.w(TAG, "Client connection timed out")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun checkRateLimit(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = rateLimitMap.getOrPut(ip) { mutableListOf() }
        synchronized(timestamps) {
            // Remove old entries outside the window
            timestamps.removeAll { it < now - RATE_LIMIT_WINDOW_MS }
            if (timestamps.size >= RATE_LIMIT_MAX_MESSAGES) {
                return false
            }
            timestamps.add(now)
        }
        return true
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
        rateLimitMap.clear()
    }
}
