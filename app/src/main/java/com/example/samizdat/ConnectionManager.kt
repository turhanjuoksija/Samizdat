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
    // (Removed: IP rate limiting is ineffective/harmful when all traffic comes from localhost 127.0.0.1 via Tor)
    
    // Emissions of received messages: Pair(SenderIP, MessageContent)
    private val _incomingMessages = MutableSharedFlow<Pair<String, String>>()
    val incomingMessages: SharedFlow<Pair<String, String>> = _incomingMessages

    suspend fun startListening(port: Int) = withContext(Dispatchers.IO) {
        if (isRunning) return@withContext
        try {
            // Bind strictly to the localhost loopback interface to prevent local network exposure
            serverSocket = ServerSocket(port, 50, java.net.InetAddress.getLoopbackAddress())
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
            // Use explicit UTF-8 encoding for streams
            val inputStream = socket.getInputStream()
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            val writer = PrintWriter(socket.getOutputStream().writer(Charsets.UTF_8), true)
            val senderIp = socket.inetAddress.hostAddress ?: "Unknown"

            // Read messages safely, bounded to MAX_MESSAGE_SIZE to prevent OOM
            val sb = StringBuilder()
            var charInput: Int
            while (reader.read().also { charInput = it } != -1) {
                val char = charInput.toChar()
                if (char == '\n') {
                    val msg = sb.toString().trim()
                    sb.clear()

                    if (msg.isEmpty()) continue

                    Log.d(TAG, "Received from $senderIp: ${msg.take(200)}${if (msg.length > 200) "..." else ""}")
                    _incomingMessages.emit(senderIp to msg)
                    writer.println("OK")
                } else {
                    sb.append(char)
                    if (sb.length > MAX_MESSAGE_SIZE) {
                        Log.w(TAG, "Message from $senderIp exceeded length limit, dropping connection")
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

    // IP-based checkRateLimit removed

    suspend fun sendMessage(ip: String, port: Int, message: String, socksPort: Int) = withContext(Dispatchers.IO) {
        val finalIp = OnionUtils.ensureOnionSuffix(ip)
        
        // SECURITY: Reject non-onion addresses to prevent Tor bypass
        if (!OnionUtils.isOnion(finalIp)) {
            Log.e(TAG, "SECURITY: Refusing to connect to non-onion address: $finalIp")
            throw SecurityException("All connections must use .onion addresses via Tor")
        }
        
        val targetPort = 80 // Tor hidden services always use port 80
        try {
            Log.d(TAG, "Connecting to $finalIp:$targetPort via Tor SOCKS proxy :$socksPort")
            
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val socket = Socket(proxy).apply {
                connect(InetSocketAddress.createUnresolved(finalIp, targetPort), 30000) 
            }
            
            val writer = PrintWriter(socket.getOutputStream().writer(Charsets.UTF_8), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
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
             Log.e(TAG, "Hint: Onion connections can take several minutes to become available after Tor initialization.")
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
}
