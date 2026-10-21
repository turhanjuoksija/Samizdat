package com.example.samizdat

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.CantContinueException
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.BitmapTileSourceBase
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * osmdroid tile downloader that fetches map tiles **only through Tor's SOCKS proxy**.
 *
 * - OkHttp hands the unresolved hostname to Tor, so there is no local DNS lookup either.
 *   (osmdroid's own `Configuration.httpProxy` uses `HttpURLConnection`, which resolves DNS
 *   locally on Android - that is why we replace the downloader instead of setting a proxy.)
 * - Strict: while Tor is not ready, download threads wait (up to [TOR_WAIT_MS]) and then give up.
 *   There is **no clearnet fallback**; previously cached tiles are still shown from osmdroid's cache.
 */
class TorTileDownloader : TileDownloader() {

    companion object {
        private const val TAG = "TorTileDownloader"
        private const val TOR_WAIT_MS = 90_000L
        private const val TOR_POLL_MS = 500L
        private const val MAX_TILE_BYTES = 2L * 1024 * 1024

        /**
         * Returns the Tor SOCKS port once Tor is fully bootstrapped, else null.
         * Set from [MainActivity] before the map is created (osmdroid instantiates the
         * downloader inside its provider constructor, so this cannot be a constructor parameter).
         */
        @Volatile
        var socksPortProvider: () -> Int? = { null }

        private var cachedClient: Pair<Int, OkHttpClient>? = null

        @Synchronized
        private fun clientFor(port: Int): OkHttpClient {
            cachedClient?.let { (p, c) -> if (p == port) return c }
            val client = OkHttpClient.Builder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
            cachedClient = port to client
            return client
        }
    }

    private fun awaitTorPort(): Int? {
        val deadline = System.currentTimeMillis() + TOR_WAIT_MS
        while (true) {
            socksPortProvider()?.let { return it }
            if (System.currentTimeMillis() >= deadline) return null
            try {
                Thread.sleep(TOR_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
    }

    override fun downloadTile(
        pMapTileIndex: Long,
        pRedirectCount: Int,
        pTargetURL: String?,
        pFilesystemCache: IFilesystemCache?,
        pTileSource: OnlineTileSourceBase?
    ): Drawable? {
        if (pTargetURL.isNullOrEmpty() || pTileSource == null) return null
        val port = awaitTorPort() ?: run {
            Log.d(TAG, "Tor not ready - not loading tile (no clearnet fallback)")
            return null
        }

        val request = Request.Builder()
            .url(pTargetURL)
            .header("User-Agent", RoutingService.USER_AGENT)
            .build()

        val bytes = try {
            clientFor(port).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Tile HTTP ${response.code} via Tor")
                    return null
                }
                val contentType = response.header("Content-Type")
                if (contentType != null && !contentType.contains("image")) {
                    Log.w(TAG, "Tile response is not an image ($contentType)")
                    return null
                }
                val body = response.body ?: return null
                if (body.contentLength() > MAX_TILE_BYTES) return null
                val data = body.bytes()
                if (data.isEmpty() || data.size > MAX_TILE_BYTES) return null

                val expiration = computeExpirationTime(
                    response.header("Expires"),
                    response.header("Cache-Control"),
                    System.currentTimeMillis()
                )
                pFilesystemCache?.saveFile(pTileSource, pMapTileIndex, ByteArrayInputStream(data), expiration)
                data
            }
        } catch (e: IOException) {
            Log.w(TAG, "Tile download via Tor failed: ${e.message}")
            return null
        }

        return try {
            pTileSource.getDrawable(ByteArrayInputStream(bytes))
        } catch (e: BitmapTileSourceBase.LowMemoryException) {
            // Same contract as osmdroid's default downloader
            throw CantContinueException(e)
        }
    }
}

/**
 * Standard osmdroid tile provider (memory + filesystem cache, approximation) whose only
 * network module downloads through [TorTileDownloader].
 */
class TorMapTileProvider(context: Context, tileSource: ITileSource) :
    MapTileProviderBasic(context, tileSource) {

    override fun createDownloaderProvider(
        pNetworkAvailabilityCheck: INetworkAvailablityCheck?,
        pTileSource: ITileSource?
    ): MapTileDownloader = super.createDownloaderProvider(pNetworkAvailabilityCheck, pTileSource).apply {
        setTileDownloader(TorTileDownloader())
    }
}
