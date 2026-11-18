package com.example.samizdat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
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
        private const val MAX_CONCURRENT_CONNECTIONS = 16 // connections beyond this are dropped immediately
        private const val MAX_HTTP_LINE = 4_096
        private const val MAX_HTTP_HEADER_BYTES = 8_192
        private const val MAX_HTTP_HEADERS = 50
        private const val ONION_CONNECT_TIMEOUT_MS = 90_000
        private const val SEND_MAX_ATTEMPTS = 3
        private const val SEND_RETRY_DELAY_MS = 5_000L
    }

    // Bounds the number of simultaneously handled connections (a long APK upload must not block messaging,
    // but unlimited concurrency would be a DoS vector).
    private val connectionSlots = java.util.concurrent.Semaphore(MAX_CONCURRENT_CONNECTIONS)

    /**
     * Handler for plain HTTP GET requests on the onion service (used to serve update APKs, §20).
     * Receives the request line, lower-cased headers and the socket. Null = respond 404.
     */
    @Volatile
    var httpHandler: (suspend (requestLine: String, headers: Map<String, String>, socket: Socket) -> Unit)? = null

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
            
            coroutineScope {
                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        if (!connectionSlots.tryAcquire()) {
                            Log.w(TAG, "Too many concurrent connections, dropping new one")
                            try { clientSocket.close() } catch (_: Exception) { }
                            continue
                        }
                        // Handle each client concurrently so a long-running transfer does not block others
                        launch {
                            try {
                                handleClient(clientSocket)
                            } finally {
                                connectionSlots.release()
                            }
                        }
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "Error accepting connection", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val inputStream = java.io.BufferedInputStream(socket.getInputStream())

            // Peek at the first bytes: "GET " = HTTP request (update download), otherwise JSON-line protocol
            inputStream.mark(8)
            val prefix = ByteArray(4)
            var prefixLen = 0
            while (prefixLen < 4) {
                val r = inputStream.read(prefix, prefixLen, 4 - prefixLen)
                if (r == -1) break
                prefixLen += r
            }
            inputStream.reset()
            if (prefixLen == 4 && String(prefix, Charsets.US_ASCII) == "GET ") {
                handleHttpRequest(socket, inputStream)
                return@withContext
            }

            // Use explicit UTF-8 encoding for streams
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

    /** Reads an HTTP request head (bounded) and passes it to [httpHandler]. */
    private suspend fun handleHttpRequest(socket: Socket, input: java.io.InputStream) {
        val requestLine = readAsciiLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        var total = requestLine.length
        while (true) {
            val line = readAsciiLine(input) ?: return
            if (line.isEmpty()) break
            total += line.length
            if (total > MAX_HTTP_HEADER_BYTES || headers.size >= MAX_HTTP_HEADERS) {
                Log.w(TAG, "HTTP request head too large, dropping")
                return
            }
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        Log.i(TAG, "HTTP request: ${requestLine.take(100)}")
        val handler = httpHandler
        if (handler == null) {
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
            return
        }
        handler(requestLine, headers, socket)
    }

    /** Reads one CRLF/LF-terminated ASCII line, or null on EOF / over-long line. */
    private fun readAsciiLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > MAX_HTTP_LINE) return null
        }
        return sb.toString()
    }

    suspend fun sendMessage(ip: String, port: Int, message: String, socksPort: Int) = withContext(Dispatchers.IO) {
        val finalIp = OnionUtils.ensureOnionSuffix(ip)
        
        // SECURITY: Reject non-onion addresses to prevent Tor bypass
        if (!OnionUtils.isOnion(finalIp)) {
            Log.e(TAG, "SECURITY: Refusing to connect to non-onion address: $finalIp")
            throw SecurityException("All connections must use .onion addresses via Tor")
        }
        
        val targetPort = 80 // Tor hidden services always use port 80
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

        for (attempt in 1..SEND_MAX_ATTEMPTS) {
            try {
                Log.d(TAG, "Connecting to $finalIp:$targetPort via Tor SOCKS proxy :$socksPort (attempt $attempt/$SEND_MAX_ATTEMPTS)")

                Socket(proxy).use { socket ->
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    socket.connect(InetSocketAddress.createUnresolved(finalIp, targetPort), ONION_CONNECT_TIMEOUT_MS)

                    val writer = PrintWriter(socket.getOutputStream().writer(Charsets.UTF_8), true)
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    writer.println(message)

                    // Wait for ACK
                    val response = reader.readLine()
                    if (response != "OK") {
                        throw java.io.IOException("No ACK received from recipient (got: $response)")
                    }
                }

                Log.d(TAG, "Message sent and delivered to $finalIp (attempt $attempt)")
                return@withContext
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Attempt $attempt/$SEND_MAX_ATTEMPTS to $finalIp:$targetPort failed: ${e.message}")
                if (attempt < SEND_MAX_ATTEMPTS) {
                    kotlinx.coroutines.delay(SEND_RETRY_DELAY_MS * attempt)
                } else {
                    Log.e(TAG, "Failed to send message to $finalIp:$targetPort after $SEND_MAX_ATTEMPTS attempts. Error: ${e.message}", e)
                    Log.e(TAG, "Hint: Onion connections can take several minutes to become available after Tor initialization.")
                    throw e
                }
            }
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
