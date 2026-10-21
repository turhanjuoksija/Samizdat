package com.example.samizdat

import android.util.Log
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Fully offline road routing using BRouter (`brouter-core`).
 *
 * No network access is ever made here: the route is computed on-device from the `.rd5`
 * segments managed by [SegmentManager]. If a leg needs a segment that is not installed,
 * [route] returns null so [RouteManager] can try the next engine.
 */
class BRouterEngine(private val segments: SegmentManager) : RouteEngine {

    override val name = "BRouter (offline)"

    companion object {
        private const val TAG = "BRouterEngine"
        private const val PROFILE_NAME = "car-vario.brf"
        private const val LOOKUPS_NAME = "lookups.dat"
        private const val ASSET_DIR = "brouter"
        private const val MAX_RUNNING_TIME_MS = 30_000L

        // BRouter keeps static caches (profiles, nodes), so legs are routed one at a time.
        private val engineLock = Mutex()
    }

    private val profileDir = File(segments.context.filesDir, "brouter/profiles2")

    /** Copies the bundled profile and `lookups.dat` into filesDir (BRouter needs real files). */
    private fun ensureProfileFiles(): File {
        profileDir.mkdirs()
        for (fileName in listOf(PROFILE_NAME, LOOKUPS_NAME)) {
            val target = File(profileDir, fileName)
            segments.context.assets.open("$ASSET_DIR/$fileName").use { input ->
                val bytes = input.readBytes()
                if (!target.isFile || target.length() != bytes.size.toLong()) {
                    target.writeBytes(bytes)
                }
            }
        }
        return File(profileDir, PROFILE_NAME)
    }

    private fun toNode(point: GeoPoint, label: String) = OsmNodeNamed().apply {
        name = label
        ilon = ((point.longitude + 180.0) * 1_000_000.0 + 0.5).toInt()
        ilat = ((point.latitude + 90.0) * 1_000_000.0 + 0.5).toInt()
    }

    override suspend fun route(start: GeoPoint, end: GeoPoint): List<GeoPoint>? {
        val needed = SegmentManager.segmentsCovering(
            start.latitude, start.longitude, end.latitude, end.longitude
        )
        if (!segments.hasAll(needed)) {
            Log.d(TAG, "Missing segments $needed - skipping offline routing")
            return null
        }

        return engineLock.withLock {
            withContext(Dispatchers.Default) {
                try {
                    val profile = ensureProfileFiles()
                    val rc = RoutingContext().apply { localFunction = profile.absolutePath }
                    val engine = RoutingEngine(
                        null, null, segments.segmentDir,
                        listOf(toNode(start, "from"), toNode(end, "to")), rc
                    )
                    engine.quite = true

                    // Abort BRouter's search loop if the calling coroutine is cancelled
                    val handle = coroutineContext[Job]?.invokeOnCompletion { cause ->
                        if (cause != null) engine.terminate()
                    }
                    try {
                        engine.doRun(MAX_RUNNING_TIME_MS)
                    } finally {
                        handle?.dispose()
                    }

                    val error = engine.errorMessage
                    val nodes = engine.foundTrack?.nodes
                    if (error != null || nodes.isNullOrEmpty()) {
                        Log.w(TAG, "BRouter failed: ${error ?: "empty track"}")
                        return@withContext null
                    }

                    nodes.map { GeoPoint(it.iLat / 1_000_000.0 - 90.0, it.iLon / 1_000_000.0 - 180.0) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.e(TAG, "BRouter threw", e)
                    null
                }
            }
        }
    }
}
