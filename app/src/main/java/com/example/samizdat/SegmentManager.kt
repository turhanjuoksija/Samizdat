package com.example.samizdat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.math.floor

/**
 * Manages BRouter routing-data segments (`.rd5`, 5°x5° tiles) stored in
 * `filesDir/brouter/segments4/`.
 *
 * Segments are downloaded **through the Tor SOCKS proxy** (same pattern as [UpdateManager]),
 * so the download only reveals "someone fetched tile E20_N60", never the user's IP or routes.
 * Routing itself never touches the network once the needed segments are installed.
 */
class SegmentManager(
    val context: Context,
    private val socksPort: () -> Int?
) {
    data class Segment(val name: String, val sizeBytes: Long)

    /** A predefined downloadable region shown in Settings. [approxMb] is only a UI hint. */
    data class Region(val name: String, val label: String, val approxMb: Int)

    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val segment: String,
            val bytesDone: Long,
            val bytesTotal: Long,
            val index: Int = 1,
            val count: Int = 1
        ) : DownloadState()
        object Done : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    companion object {
        private const val TAG = "SegmentManager"
        private const val BASE_URL = "https://brouter.de/brouter/segments4"

        // Largest Finnish segment is ~40 MB; 100 MB protects against storage exhaustion.
        private const val MAX_SEGMENT_BYTES = 100L * 1024 * 1024
        // Smallest Finnish-area segment (E15_N70) is ~70 KB.
        private const val MIN_SEGMENT_BYTES = 50L * 1024

        /**
         * Segments covering Finland, most useful first. Sizes from brouter.de/brouter/segments4 (10/2026).
         * Each segment is a 5°x5° tile, so most of them also cover parts of neighbouring countries.
         */
        val FINLAND_REGIONS = listOf(
            Region("E20_N60", "Southwest: Helsinki, Turku, Tampere, Vaasa", 37),
            Region("E25_N60", "East: Lahti, Kouvola, Jyväskylä, Kuopio, Joensuu", 37),
            Region("E25_N65", "North: Oulu, Rovaniemi, Kuusamo, Inari", 10),
            Region("E20_N65", "West Lapland: Tornio, Kemi, Kittilä, Muonio", 11),
            Region("E15_N60", "Åland (Mariehamn) + Stockholm area", 17),
            Region("E20_N55", "Hanko tip + Estonia/Latvia west", 22),
            Region("E25_N55", "Gulf of Finland south shore: Tallinn, Estonia east", 23),
            Region("E30_N60", "Karelian border: Ilomantsi, Lieksa east", 12),
            Region("E25_N70", "Far north: Utsjoki, Nuorgam", 2)
        )

        /** BRouter file name of the 5°x5° segment containing the point, e.g. `E20_N60`. */
        fun segmentName(lat: Double, lon: Double): String {
            val lonBase = floor(lon / 5.0).toInt() * 5
            val latBase = floor(lat / 5.0).toInt() * 5
            val ew = if (lonBase >= 0) "E$lonBase" else "W${-lonBase}"
            val ns = if (latBase >= 0) "N$latBase" else "S${-latBase}"
            return "${ew}_$ns"
        }

        /** All segments covering the bounding box spanned by the two points. */
        fun segmentsCovering(aLat: Double, aLon: Double, bLat: Double, bLon: Double): List<String> {
            val names = linkedSetOf<String>()
            var lon = floor(minOf(aLon, bLon) / 5.0).toInt() * 5
            val lonEnd = floor(maxOf(aLon, bLon) / 5.0).toInt() * 5
            val latStart = floor(minOf(aLat, bLat) / 5.0).toInt() * 5
            val latEnd = floor(maxOf(aLat, bLat) / 5.0).toInt() * 5
            while (lon <= lonEnd) {
                var lat = latStart
                while (lat <= latEnd) {
                    names.add(segmentName(lat.toDouble(), lon.toDouble()))
                    lat += 5
                }
                lon += 5
            }
            return names.toList()
        }
    }

    val segmentDir: File = File(context.filesDir, "brouter/segments4").apply { mkdirs() }

    private val _installed = MutableStateFlow<List<Segment>>(emptyList())
    val installed: StateFlow<List<Segment>> = _installed.asStateFlow()

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    @Volatile
    private var activeCall: okhttp3.Call? = null

    init {
        refresh()
    }

    private fun fileFor(name: String) = File(segmentDir, "$name.rd5")

    fun isInstalled(name: String): Boolean = fileFor(name).let { it.isFile && it.length() >= MIN_SEGMENT_BYTES }

    fun hasAll(names: List<String>): Boolean = names.all { isInstalled(it) }

    /** Re-scan the segment directory and publish the result. */
    fun refresh() {
        _installed.value = segmentDir.listFiles { f -> f.isFile && f.name.endsWith(".rd5") }
            ?.map { Segment(it.name.removeSuffix(".rd5"), it.length()) }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun delete(name: String) {
        fileFor(name).delete()
        refresh()
    }

    /**
     * Download missing segments over Tor. Updates [downloadState] as it goes.
     * Already installed segments are skipped.
     */
    suspend fun download(names: List<String>) = withContext(Dispatchers.IO) {
        if (_downloadState.value is DownloadState.Downloading) return@withContext
        val missing = names.distinct().filterNot { isInstalled(it) }
        if (missing.isEmpty()) {
            _downloadState.value = DownloadState.Done
            return@withContext
        }

        val port = socksPort()
        if (port == null) {
            _downloadState.value = DownloadState.Error("Tor is not ready yet. Try again in a moment.")
            return@withContext
        }

        // With a SOCKS proxy OkHttp hands the *unresolved* hostname to Tor, so there is no local DNS leak.
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        try {
            missing.forEachIndexed { i, name ->
                downloadOne(client, name, i + 1, missing.size)
            }
            _downloadState.value = DownloadState.Done
        } catch (e: CancellationException) {
            Log.i(TAG, "Segment download cancelled")
            _downloadState.value = DownloadState.Idle
            throw e
        } catch (e: Exception) {
            // Call.cancel() surfaces as IOException("Canceled"); treat it as a user cancel
            if (!kotlin.coroutines.coroutineContext.isActive) {
                _downloadState.value = DownloadState.Idle
                throw CancellationException("Segment download cancelled")
            }
            Log.e(TAG, "Segment download failed", e)
            _downloadState.value = DownloadState.Error(e.message ?: e.javaClass.simpleName)
        } finally {
            refresh()
        }
    }

    private suspend fun downloadOne(client: OkHttpClient, name: String, index: Int, count: Int) {
        val target = fileFor(name)
        val partial = File(segmentDir, "$name.rd5.part")
        partial.delete()

        val request = Request.Builder()
            .url("$BASE_URL/$name.rd5")
            .header("User-Agent", RoutingService.USER_AGENT)
            .build()

        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $name")
                val body = response.body ?: throw IOException("Empty response for $name")
                val total = body.contentLength()
                if (total > MAX_SEGMENT_BYTES) throw IOException("Segment $name too large ($total bytes)")

                var done = 0L
                var lastReported = 0L
                _downloadState.value = DownloadState.Downloading(name, 0, total, index, count)
                body.byteStream().use { input ->
                    FileOutputStream(partial).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            kotlin.coroutines.coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n == -1) break
                            done += n
                            if (done > MAX_SEGMENT_BYTES) throw IOException("Segment $name exceeded size limit")
                            output.write(buffer, 0, n)
                            if (done - lastReported >= 256 * 1024) {
                                lastReported = done
                                _downloadState.value = DownloadState.Downloading(name, done, total, index, count)
                            }
                        }
                    }
                }
                if (total > 0 && done != total) throw IOException("Incomplete download of $name ($done/$total bytes)")
                if (done < MIN_SEGMENT_BYTES) throw IOException("Segment $name is suspiciously small ($done bytes)")
                target.delete()
                if (!partial.renameTo(target)) throw IOException("Could not store segment $name")
                Log.i(TAG, "Installed $name (${done / 1024} KB)")
            }
        } finally {
            activeCall = null
            partial.delete()
        }
    }

    /**
     * Aborts the in-flight HTTP call. Blocking Tor connects/reads don't observe coroutine
     * cancellation, so callers cancel the coroutine *and* call this.
     */
    fun cancelActiveDownload() {
        activeCall?.cancel()
    }

    /** Reset an Error/Done state back to Idle (after the UI has shown it). */
    fun clearDownloadState() {
        if (_downloadState.value !is DownloadState.Downloading) _downloadState.value = DownloadState.Idle
    }
}
