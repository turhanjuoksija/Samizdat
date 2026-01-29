package com.example.samizdat

import org.osmdroid.util.GeoPoint
import kotlin.math.*

object RadioGridUtils {
    // ~2km in degrees (approximate, refined by latitude)
    private const val BASE_STEP = 0.018 

    /**
     * Calculates a unique Grid ID for a given coordinate.
     * The grid cells are approximately 2km x 2km.
     */
    fun getGridId(lat: Double, lon: Double): String {
        // Latitude index
        val latIdx = floor(lat / BASE_STEP).toInt()
        
        // Longitude step increases as we move away from the equator
        // at 60 deg (Finland), cos is 0.5, so lon degrees are half as wide
        val cosLat = cos(Math.toRadians(lat)).coerceAtLeast(0.1) // Avoid division by zero
        val lonStep = BASE_STEP / cosLat
        val lonIdx = floor(lon / lonStep).toInt()
        
        return "RG-$latIdx-$lonIdx"
    }

    /**
     * Convert Grid ID to a SHA-1 hash for DHT operations.
     */
    fun getGridHash(gridId: String): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
        return digest.digest(gridId.toByteArray(Charsets.UTF_8))
    }

    /**
     * Calculates all grid IDs that a straight line between two points passes through.
     * Used by drivers to broadcast their route to relevant 'channels'.
     * Simplification: Just samples the line every 1km.
     */
    fun getRouteGrids(start: GeoPoint, end: GeoPoint): List<String> {
        val grids = mutableSetOf<String>()
        
        val distResults = FloatArray(1)
        android.location.Location.distanceBetween(
            start.latitude, start.longitude,
            end.latitude, end.longitude,
            distResults
        )
        val totalDistMeters = distResults[0]
        
        // Sample every 1000m to ensure we catch all 2km cells
        val steps = (totalDistMeters / 1000).toInt().coerceAtLeast(1)
        
        for (i in 0..steps) {
            val fraction = i.toDouble() / steps
            val interpolatedLat = start.latitude + (end.latitude - start.latitude) * fraction
            val interpolatedLon = start.longitude + (end.longitude - start.longitude) * fraction
            grids.add(getGridId(interpolatedLat, interpolatedLon))
        }
        
        return grids.toList()
    }

    /**
     * Calculate all grid IDs from a polyline (road-based route).
     * More accurate than straight-line interpolation.
     */
    fun getRouteGridsFromPolyline(points: List<GeoPoint>): List<String> {
        return points.map { getGridId(it.latitude, it.longitude) }.distinct()
    }

    /**
     * Get the bounding box for a grid cell (for visualization).
     * Returns Pair(southWest, northEast) corners.
     */
    fun getGridBounds(gridId: String): Pair<GeoPoint, GeoPoint>? {
        // Parse "RG-latIdx-lonIdx"
        val parts = gridId.split("-")
        if (parts.size != 3) return null
        
        val latIdx = parts[1].toIntOrNull() ?: return null
        val lonIdx = parts[2].toIntOrNull() ?: return null
        
        val latMin = latIdx * BASE_STEP
        val latMax = latMin + BASE_STEP
        
        // Approximate longitude (use center latitude for calculation)
        val centerLat = (latMin + latMax) / 2.0
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
        val lonStep = BASE_STEP / cosLat
        
        val lonMin = lonIdx * lonStep
        val lonMax = lonMin + lonStep
        
        return Pair(GeoPoint(latMin, lonMin), GeoPoint(latMax, lonMax))
    }
    /**
     * Get neighboring grid IDs within a certain radius.
     * Radius 1 = 3x3 (9 cells), Radius 2 = 5x5 (25 cells).
     */
    fun getNeighborGrids(gridId: String, radius: Int): List<String> {
        if (radius <= 0) return listOf(gridId)
        val parts = gridId.split("-")
        if (parts.size != 3) return listOf(gridId)
        
        val baseLatIdx = parts[1].toIntOrNull() ?: return listOf(gridId)
        val baseLonIdx = parts[2].toIntOrNull() ?: return listOf(gridId)
        
        val neighbors = mutableSetOf<String>()
        for (dl in -radius..radius) {
            for (dg in -radius..radius) {
                neighbors.add("RG-${baseLatIdx + dl}-${baseLonIdx + dg}")
            }
        }
        return neighbors.toList()
    }

    /**
     * Get the center point of a grid cell.
     */
    fun getGridCenter(gridId: String): GeoPoint? {
        val bounds = getGridBounds(gridId) ?: return null
        val (sw, ne) = bounds
        val centerLat = (sw.latitude + ne.latitude) / 2.0
        val centerLon = (sw.longitude + ne.longitude) / 2.0
        return GeoPoint(centerLat, centerLon)
    }
}
