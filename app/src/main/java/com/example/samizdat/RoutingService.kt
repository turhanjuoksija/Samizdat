package com.example.samizdat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.URL

/**
 * Service for fetching road-based routes using OSRM (Open Source Routing Machine).
 * Uses the free OSRM demo server.
 */
object RoutingService {
    private const val TAG = "RoutingService"
    private const val OSRM_BASE_URL = "https://router.project-osrm.org/route/v1/driving"

    /**
     * Fetch a road-following route between two points.
     * @return List of GeoPoints forming the route polyline, or null if failed.
     */
    suspend fun getRoute(start: GeoPoint, end: GeoPoint, waypoint: GeoPoint? = null): List<GeoPoint>? = withContext(Dispatchers.IO) {
        try {
            // OSRM URL format: /driving/lon1,lat1;[lon_way,lat_way];lon2,lat2?overview=full&geometries=geojson
            val pointsStr = if (waypoint != null) {
                "${start.longitude},${start.latitude};${waypoint.longitude},${waypoint.latitude};${end.longitude},${end.latitude}"
            } else {
                "${start.longitude},${start.latitude};${end.longitude},${end.latitude}"
            }
            val url = "$OSRM_BASE_URL/$pointsStr?overview=full&geometries=geojson"
            Log.d(TAG, "Fetching route: $url")

            val response = URL(url).readText()
            val json = JSONObject(response)

            if (json.getString("code") != "Ok") {
                Log.e(TAG, "OSRM error: ${json.optString("message", "Unknown")}")
                return@withContext null
            }

            val routes = json.getJSONArray("routes")
            if (routes.length() == 0) {
                Log.w(TAG, "No routes found")
                return@withContext null
            }

            val geometry = routes.getJSONObject(0).getJSONObject("geometry")
            val coordinates = geometry.getJSONArray("coordinates")

            val points = mutableListOf<GeoPoint>()
            for (i in 0 until coordinates.length()) {
                val coord = coordinates.getJSONArray(i)
                val lon = coord.getDouble(0)
                val lat = coord.getDouble(1)
                points.add(GeoPoint(lat, lon))
            }

            Log.d(TAG, "Route fetched with ${points.size} points")
            points
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch route", e)
            null
        }
    }

    /**
     * Get route summary (distance in km, duration in minutes)
     */
    suspend fun getRouteSummary(start: GeoPoint, end: GeoPoint): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            val url = "$OSRM_BASE_URL/${start.longitude},${start.latitude};${end.longitude},${end.latitude}?overview=false"
            val response = URL(url).readText()
            val json = JSONObject(response)

            if (json.getString("code") != "Ok") return@withContext null

            val route = json.getJSONArray("routes").getJSONObject(0)
            val distanceMeters = route.getDouble("distance")
            val durationSeconds = route.getDouble("duration")

            val distanceKm = distanceMeters / 1000.0
            val durationMinutes = durationSeconds / 60.0

            Pair(distanceKm, durationMinutes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get route summary", e)
            null
        }
    }
}
