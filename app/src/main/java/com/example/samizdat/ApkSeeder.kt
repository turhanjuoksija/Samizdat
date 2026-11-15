package com.example.samizdat

import android.content.Context
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serves this phone's own installed APK to peers over the onion service (DEVELOPMENT_NOTES §20).
 *
 * The phone only seeds after it has verified that its installed APK (applicationInfo.sourceDir)
 * is byte-identical to a developer-signed [UpdateManifest] (same size + SHA-256).
 *
 * DoS limits: every Tor connection arrives from 127.0.0.1, so per-client limits are impossible.
 * All limits are therefore global and fixed, which bounds the worst-case cost of seeding:
 *  - 1 upload at a time, at most [MAX_UPLOADS_PER_HOUR] uploads per hour
 *  - at most [DAILY_BYTE_BUDGET] bytes per rolling 24 h (persisted)
 *  - each upload is aborted after [MAX_UPLOAD_MS] (slow-reader protection)
 *  - only on unmetered networks (unless allowed) and with battery >= [MIN_BATTERY_PCT] or charging
 */
class ApkSeeder(private val context: Context, private val currentVersionCode: Int) {

    data class Stats(
        val uploadsLastHour: Int = 0,
        val bytesToday: Long = 0,
        val uploading: Boolean = false
    )

    private val prefs = context.getSharedPreferences("apk_seeder", Context.MODE_PRIVATE)

    private val _seedManifest = MutableStateFlow<UpdateManifest?>(null)
    /** Verified manifest of the APK this phone is able to serve, or null. */
    val seedManifest: StateFlow<UpdateManifest?> = _seedManifest.asStateFlow()

    private val _status = MutableStateFlow("Not sharing: waiting for a signed release manifest")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private val _sharingEnabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val sharingEnabled: StateFlow<Boolean> = _sharingEnabled.asStateFlow()

    private val _allowMetered = MutableStateFlow(prefs.getBoolean(KEY_ALLOW_METERED, false))
    val allowMetered: StateFlow<Boolean> = _allowMetered.asStateFlow()

    private val uploading = AtomicBoolean(false)
    private val uploadStarts = ArrayDeque<Long>()
    private val lock = Any()

    fun setSharingEnabled(enabled: Boolean) {
        _sharingEnabled.value = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setAllowMetered(allow: Boolean) {
        _allowMetered.value = allow
        prefs.edit().putBoolean(KEY_ALLOW_METERED, allow).apply()
    }

    /**
     * Starts seeding if [manifest] (already signature-verified by the caller) describes exactly the
     * installed APK. Hashes ~25 MB once; call off the main thread (it switches to IO itself).
     */
    suspend fun offerSelf(manifest: UpdateManifest): Boolean = withContext(Dispatchers.IO) {
        if (manifest.version != currentVersionCode) return@withContext false
        if (manifest.sameReleaseAs(_seedManifest.value)) return@withContext true
        try {
            val appInfo = context.applicationInfo
            if (!appInfo.splitSourceDirs.isNullOrEmpty()) {
                _status.value = "Not sharing: app is installed as split APKs"
                return@withContext false
            }
            val apk = File(appInfo.sourceDir)
            if (apk.length() != manifest.size) {
                _status.value = "Not sharing: installed APK is not the signed release v${manifest.version} (size differs)"
                return@withContext false
            }
            if (UpdateManifest.sha256Hex(apk) != manifest.sha256) {
                _status.value = "Not sharing: installed APK does not match the signed release v${manifest.version}"
                return@withContext false
            }
            _seedManifest.value = manifest
            _status.value = "Sharing v${manifest.version} with peers (signed release verified)"
            Log.i(TAG, "Seeding own APK v${manifest.version} (${manifest.size} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Self-verification failed", e)
            _status.value = "Not sharing: could not read installed APK (${e.message})"
            false
        }
    }

    /** Returns null if sharing is currently allowed, else a human-readable reason. */
    fun policyBlockReason(): String? {
        if (!_sharingEnabled.value) return "sharing disabled"
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (cm.isActiveNetworkMetered && !_allowMetered.value) return "on metered/mobile data"
        } catch (e: Exception) {
            return "network state unknown"
        }
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        if (bm != null) {
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (pct in 0 until MIN_BATTERY_PCT && !bm.isCharging) return "battery low ($pct%)"
        }
        return null
    }

    // ---------------- Quota bookkeeping ----------------

    private fun pruneHourly(now: Long) {
        while (uploadStarts.isNotEmpty() && now - uploadStarts.first() > HOUR_MS) uploadStarts.removeFirst()
    }

    /** Bytes used in the current rolling 24 h window (persisted across restarts). */
    private fun bytesInWindow(now: Long): Long {
        val windowStart = prefs.getLong(KEY_WINDOW_START, 0L)
        if (now - windowStart > DAY_MS || windowStart > now) {
            prefs.edit().putLong(KEY_WINDOW_START, now).putLong(KEY_WINDOW_BYTES, 0L).apply()
            return 0L
        }
        return prefs.getLong(KEY_WINDOW_BYTES, 0L)
    }

    private fun addBytes(now: Long, bytes: Long) {
        val used = bytesInWindow(now)
        prefs.edit().putLong(KEY_WINDOW_BYTES, used + bytes).apply()
    }

    private fun publishStats() {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            pruneHourly(now)
            _stats.value = Stats(uploadStarts.size, bytesInWindow(now), uploading.get())
        }
    }

    /** Reserves the single upload slot if all quotas allow [bytesNeeded]. Returns a refusal reason or null. */
    private fun tryReserve(bytesNeeded: Long): String? {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            pruneHourly(now)
            if (uploadStarts.size >= MAX_UPLOADS_PER_HOUR) return "hourly limit reached"
            if (bytesInWindow(now) + bytesNeeded > DAILY_BYTE_BUDGET) return "daily limit reached"
            if (!uploading.compareAndSet(false, true)) return "busy"
            uploadStarts.addLast(now)
        }
        publishStats()
        return null
    }

    // ---------------- HTTP serving ----------------

    /**
     * Handles "GET /update_v<N>.apk" requests arriving on the onion service.
     * Wired via [ConnectionManager.httpHandler].
     */
    suspend fun handleHttp(requestLine: String, headers: Map<String, String>, socket: Socket) {
        val out = socket.getOutputStream()
        val parts = requestLine.split(" ")
        if (parts.size < 2 || parts[0] != "GET") return respond(out, 400, "Bad Request")

        val requested = UpdateManifest.versionFromPath(parts[1]) ?: return respond(out, 404, "Not Found")
        val seed = _seedManifest.value
        if (seed == null || requested != seed.version) return respond(out, 404, "Not Found")

        policyBlockReason()?.let { reason ->
            Log.i(TAG, "Refusing upload: $reason")
            return respond(out, 503, "Service Unavailable", mapOf("Retry-After" to "3600"))
        }

        val apk = File(context.applicationInfo.sourceDir)
        if (apk.length() != seed.size) return respond(out, 404, "Not Found")

        val start = parseRangeStart(headers["range"]) ?: 0L
        if (start >= seed.size) {
            return respond(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */${seed.size}"))
        }
        val length = seed.size - start

        tryReserve(length)?.let { reason ->
            Log.i(TAG, "Refusing upload: $reason")
            return respond(out, 503, "Service Unavailable", mapOf("Retry-After" to "600"))
        }

        var sent = 0L
        try {
            val headerLines = buildString {
                append(if (start > 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/vnd.android.package-archive\r\n")
                append("Content-Length: $length\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (start > 0) append("Content-Range: bytes $start-${seed.size - 1}/${seed.size}\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(headerLines.toByteArray(Charsets.US_ASCII))

            coroutineScope {
                // Hard cap on upload duration: a blocked write is not interrupted by soTimeout.
                val watchdog = launch {
                    delay(MAX_UPLOAD_MS)
                    Log.w(TAG, "Upload exceeded ${MAX_UPLOAD_MS / 60000} min, closing connection")
                    try { socket.close() } catch (_: Exception) { }
                }
                try {
                    withContext(Dispatchers.IO) {
                        FileInputStream(apk).use { input ->
                            var skipped = 0L
                            while (skipped < start) {
                                val s = input.skip(start - skipped)
                                if (s <= 0) throw java.io.IOException("Could not seek in APK")
                                skipped += s
                            }
                            val buffer = ByteArray(32 * 1024)
                            while (sent < length) {
                                val toRead = minOf(buffer.size.toLong(), length - sent).toInt()
                                val read = input.read(buffer, 0, toRead)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                sent += read
                            }
                            out.flush()
                        }
                    }
                } finally {
                    watchdog.cancel()
                }
            }
            Log.i(TAG, "Served v${seed.version}: $sent bytes (from offset $start)")
        } catch (e: Exception) {
            Log.w(TAG, "Upload aborted after $sent bytes: ${e.message}")
        } finally {
            addBytes(System.currentTimeMillis(), sent)
            uploading.set(false)
            publishStats()
        }
    }

    private fun parseRangeStart(range: String?): Long? {
        if (range == null) return null
        val m = Regex("""^bytes=(\d+)-$""").matchEntire(range.trim()) ?: return null
        return m.groupValues[1].toLongOrNull()
    }

    private fun respond(out: OutputStream, code: Int, reason: String, extra: Map<String, String> = emptyMap()) {
        try {
            val body = "$code $reason\n".toByteArray(Charsets.US_ASCII)
            val head = buildString {
                append("HTTP/1.1 $code $reason\r\n")
                append("Content-Type: text/plain\r\n")
                append("Content-Length: ${body.size}\r\n")
                extra.forEach { (k, v) -> append("$k: $v\r\n") }
                append("Connection: close\r\n\r\n")
            }
            out.write(head.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        } catch (_: Exception) { }
    }

    companion object {
        private const val TAG = "ApkSeeder"
        const val MAX_UPLOADS_PER_HOUR = 4
        const val DAILY_BYTE_BUDGET = 150L * 1024 * 1024
        const val MIN_BATTERY_PCT = 30
        private const val MAX_UPLOAD_MS = 20 * 60 * 1000L
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val DAY_MS = 24 * HOUR_MS

        private const val KEY_ENABLED = "sharing_enabled"
        private const val KEY_ALLOW_METERED = "allow_metered"
        private const val KEY_WINDOW_START = "window_start"
        private const val KEY_WINDOW_BYTES = "window_bytes"
    }
}

