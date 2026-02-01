package com.example.samizdat

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.osmdroid.util.GeoPoint

/**
 * RouteManager handles all routing and location-related logic.
 * - GPS position tracking
 * - Destination and waypoint management
 * - Road route calculation via OSRM
 * - Grid ID calculation for DHT
 */
class RouteManager(
    private val scope: CoroutineScope,
    private val onDebugLog: (String) -> Unit
) {
    // Current location
    var myLatitude by mutableStateOf<Double?>(null)
        private set
    var myLongitude by mutableStateOf<Double?>(null)
        private set
    
    // Destination
    var myDestLat by mutableStateOf<Double?>(null)
        private set
    var myDestLon by mutableStateOf<Double?>(null)
        private set
    
    // Grid IDs (for DHT)
    var myGridId by mutableStateOf<String?>(null)
        private set
    var myDestGridId by mutableStateOf<String?>(null)
        private set
    
    // Multi-waypoint support (Driver can add up to 6 stops)
    val myWaypoints = mutableStateListOf<GeoPoint>()
    
    // Calculated road route (polyline for map display)
    var roadRoute by mutableStateOf<List<GeoPoint>?>(null)
        private set
    
    // Grid IDs that the route passes through
    var routeGrids by mutableStateOf<List<String>>(emptyList())
        private set
    
    // Role-specific persistence
    private var driverDestLat: Double? = null
    private var driverDestLon: Double? = null
    private var passengerDestLat: Double? = null
    private var passengerDestLon: Double? = null
    
    // Max walking distance for passengers (meters)
    var maxWalkingDistanceMeters by mutableIntStateOf(500)
    
    // Callback for when route changes (to notify broadcasting logic)
    var onRouteChanged: (() -> Unit)? = null

    /**
     * Update the user's current GPS location
     */
    fun updateLocation(lat: Double, lon: Double) {
        myLatitude = lat
        myLongitude = lon
        myGridId = RadioGridUtils.getGridId(lat, lon)
    }

    /**
     * Set the destination point
     */
    fun updateDestination(lat: Double, lon: Double, role: String) {
        myDestLat = lat
        myDestLon = lon
        myDestGridId = RadioGridUtils.getGridId(lat, lon)
        
        if (role == "DRIVER") {
            driverDestLat = lat
            driverDestLon = lon
        } else {
            passengerDestLat = lat
            passengerDestLon = lon
            // Passenger clears waypoints, only one destination
            myWaypoints.clear()
        }
        onDebugLog("DESTINATION SET: $lat, $lon ($myDestGridId)")
    }

    /**
     * Add a waypoint (for Drivers) or set destination (for Passengers)
     * Returns true if waypoint was added, false if max reached
     */
    fun updateWaypoint(lat: Double, lon: Double, role: String): Boolean {
        // Notify that route is changing
        onRouteChanged?.invoke()

        if (role == "DRIVER") {
            // For Drivers, append waypoints to build a route
            if (myWaypoints.size < 6) {
                myWaypoints.add(GeoPoint(lat, lon))
                onDebugLog("ADDED STOP ${myWaypoints.size}: $lat, $lon")
                
                // For Drivers, the last point is the current "Destination"
                myDestLat = lat
                myDestLon = lon
                myDestGridId = RadioGridUtils.getGridId(lat, lon)
                return true
            } else {
                onDebugLog("MAX WAYPOINTS REACHED (6)")
                return false
            }
        } else {
            // For Passengers, treat any added point as the single destination
            updateDestination(lat, lon, role)
            return true
        }
    }

    /**
     * Clear everything - route, destination, waypoints
     */
    fun clearAll() {
        myDestLat = null
        myDestLon = null
        myDestGridId = null
        myWaypoints.clear()
        roadRoute = null
        routeGrids = emptyList()
        
        // Clear persisted state for all roles
        driverDestLat = null
        driverDestLon = null
        passengerDestLat = null
        passengerDestLon = null
        
        onDebugLog("ALL POINTS CLEARED")
    }

    /**
     * Remove the last waypoint (undo)
     */
    fun clearWaypoint(role: String) {
        // Notify that route is changing
        onRouteChanged?.invoke()

        if (myWaypoints.isNotEmpty()) {
            myWaypoints.removeLast()
            
            // Sync destination to the new last waypoint
            if (role == "DRIVER") {
                if (myWaypoints.isNotEmpty()) {
                    val last = myWaypoints.last()
                    myDestLat = last.latitude
                    myDestLon = last.longitude
                    myDestGridId = RadioGridUtils.getGridId(last.latitude, last.longitude)
                } else {
                    myDestLat = null
                    myDestLon = null
                    myDestGridId = null
                }
            }
            
            onDebugLog("REMOVED LAST WAYPOINT")
        } else {
            myDestLat = null
            myDestLon = null
            myDestGridId = null
            onDebugLog("DESTINATION CLEARED")
        }
        calculateRoute()
    }

    /**
     * Clear route when switching roles
     */
    fun syncRoleBasedRoute() {
        myDestLat = null
        myDestLon = null
        myWaypoints.clear()
        myDestGridId = null
        calculateRoute()
    }

    /**
     * Calculate road route from current location -> [waypoints] -> destination
     * Uses OSRM API for road-based routing
     */
    fun calculateRoute() {
        val startLat = myLatitude ?: return
        val startLon = myLongitude ?: return
        
        val pointsToVisit = mutableListOf<GeoPoint>()
        pointsToVisit.add(GeoPoint(startLat, startLon))
        pointsToVisit.addAll(myWaypoints)
        
        // If myDestLat is set and different from last waypoint, add it
        if (myDestLat != null && myDestLon != null) {
            val lastWp = if (myWaypoints.isNotEmpty()) myWaypoints.last() else null
            val isSameAsLast = lastWp != null && 
                lastWp.latitude == myDestLat && 
                lastWp.longitude == myDestLon
            
            if (!isSameAsLast) {
                pointsToVisit.add(GeoPoint(myDestLat!!, myDestLon!!))
            }
        }

        if (pointsToVisit.size < 2) {
            roadRoute = null
            routeGrids = emptyList()
            return
        }

        scope.launch {
            onDebugLog("ROUTE: Calculating ${pointsToVisit.size} stops...")
            val fullRoute = mutableListOf<GeoPoint>()
            
            // Calculate segments
            for (i in 0 until pointsToVisit.size - 1) {
                val segmentStart = pointsToVisit[i]
                val segmentEnd = pointsToVisit[i + 1]
                val segmentRoute = RoutingService.getRoute(segmentStart, segmentEnd, null)
                
                if (segmentRoute != null) {
                    fullRoute.addAll(segmentRoute)
                }
            }

            if (fullRoute.isNotEmpty()) {
                roadRoute = fullRoute
                routeGrids = RadioGridUtils.getRouteGridsFromPolyline(fullRoute)
                onDebugLog("ROUTE: Total ${fullRoute.size} pts, ${routeGrids.size} grids")
            } else {
                onDebugLog("ROUTE: Failed to calculate")
            }
        }
    }

    /**
     * Check if we have a valid destination set
     */
    fun hasDestination(): Boolean = myDestLat != null && myDestLon != null

    /**
     * Get destination as GeoPoint (or null)
     */
    fun getDestination(): GeoPoint? {
        val lat = myDestLat ?: return null
        val lon = myDestLon ?: return null
        return GeoPoint(lat, lon)
    }

    /**
     * Get current location as GeoPoint (or null)
     */
    fun getLocation(): GeoPoint? {
        val lat = myLatitude ?: return null
        val lon = myLongitude ?: return null
        return GeoPoint(lat, lon)
    }
}
