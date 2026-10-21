package com.example.samizdat

import org.osmdroid.util.GeoPoint

/**
 * A source of road-following routes for a single leg (start -> end).
 *
 * Engines are tried in order by [RouteManager]; the first one returning a non-empty
 * polyline wins. If every engine fails, RouteManager falls back to a straight line.
 */
interface RouteEngine {
    /** Short human-readable name used in debug logs, e.g. "BRouter (offline)". */
    val name: String

    /** @return the polyline for this leg, or null if this engine cannot route it. */
    suspend fun route(start: GeoPoint, end: GeoPoint): List<GeoPoint>?
}

/**
 * Online fallback using the OSRM demo server (clearnet!).
 * Kept only until offline routing data is installed on the device.
 */
object OsrmEngine : RouteEngine {
    override val name = "OSRM (online)"

    override suspend fun route(start: GeoPoint, end: GeoPoint): List<GeoPoint>? =
        RoutingService.getRoute(start, end, null)
}
