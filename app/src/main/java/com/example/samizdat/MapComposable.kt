package com.example.samizdat

import android.graphics.drawable.Drawable
import android.util.Log
import android.preference.PreferenceManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import android.widget.Toast
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.Polygon
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.graphics.drawable.toBitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.Path
import android.graphics.DashPathEffect
import android.location.Location

// Helper function to create a bitmap with emoji/text
fun createTextMarkerBitmap(text: String, backgroundColor: Int, textColor: Int = Color.WHITE, sizeDp: Int = 48): Bitmap {
    val density = android.content.res.Resources.getSystem().displayMetrics.density
    val sizePx = (sizeDp * density).toInt()
    
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    
    // Draw circle background
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = backgroundColor
    paint.style = Paint.Style.FILL
    val radius = sizePx / 2f
    canvas.drawCircle(radius, radius, radius * 0.9f, paint)
    
    // Draw text/emoji
    paint.color = textColor
    paint.textSize = sizePx * 0.6f
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.DEFAULT_BOLD
    
    val textBounds = android.graphics.Rect()
    paint.getTextBounds(text, 0, text.length, textBounds)
    val textY = radius - textBounds.exactCenterY()
    
    canvas.drawText(text, radius, textY, paint)
    
    return bitmap
}

// Helper function to create a Teardrop shaped marker for waypoints/start/finish
fun createTeardropMarkerBitmap(
    text: String, 
    outerColor: Int = Color.BLACK, 
    innerColor: Int = Color.GREEN, 
    textColor: Int = Color.BLACK, 
    sizeDp: Int = 56
): Bitmap {
    val density = android.content.res.Resources.getSystem().displayMetrics.density
    val sizePx = (sizeDp * density).toInt()

    // Height slightly taller for the point
    val width = sizePx
    val height = (sizePx * 1.3).toInt()

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.style = Paint.Style.FILL

    val cx = width / 2f
    // Circle center slightly higher than center of bitmap to leave room for tail
    val cy = width / 2f
    val radius = width / 2f * 0.9f

    // 1. Draw Outer Teardrop (Black)
    paint.color = outerColor
    
    canvas.drawCircle(cx, cy, radius, paint)
    
    val trianglePath = Path()
    // Wide base for triangle inside the circle
    trianglePath.moveTo(cx - radius * 0.75f, cy + radius * 0.3f) 
    trianglePath.lineTo(cx, height.toFloat())
    trianglePath.lineTo(cx + radius * 0.75f, cy + radius * 0.3f)
    trianglePath.close()
    
    canvas.drawPath(trianglePath, paint)

    // 2. Draw Inner Circle (Green)
    paint.color = innerColor
    val innerRadius = radius * 0.75f
    canvas.drawCircle(cx, cy, innerRadius, paint)
    
    // 3. Draw text/icon in the circle center
    paint.color = textColor
    paint.textSize = width * 0.40f // Slightly smaller to fit in inner circle
    paint.textAlign = Paint.Align.CENTER
    paint.typeface = Typeface.DEFAULT_BOLD

    val textBounds = android.graphics.Rect()
    paint.getTextBounds(text, 0, text.length, textBounds)
    val textY = cy - textBounds.exactCenterY()
    
    canvas.drawText(text, cx, textY, paint)

    return bitmap
}

@Composable
fun MapComposable(
    modifier: Modifier = Modifier,
    myLocation: GeoPoint?,
    myDestination: GeoPoint?,
    // Waypoints are now handled via viewModel directly or internal state
    peers: List<Peer>,
    onMapLongClick: (GeoPoint) -> Unit,
    onAddWaypoint: (GeoPoint) -> Unit = {},
    onToggleWaypointMode: () -> Unit = {},
    onClearAll: () -> Unit = {},
    onClearWaypoint: () -> Unit = {},
    isWaypointMode: Boolean = false,
    roadRoute: List<GeoPoint>? = null, // Optional road-based route polyline
    highlightedGrids: List<String>? = null, // Optional list of grid IDs to highlight
    currentGridId: String? = null, // Explicitly identify the user's current cell
    offers: List<KademliaNode.GridMessage> = emptyList(), // DHT Offers
    myRole: String = "NONE",
    mySeats: Int = 0,
    isBroadcasting: Boolean = false,
    onToggleBroadcasting: (Boolean) -> Unit = {},
    viewModel: PeersViewModel? = null
) {
    val context = LocalContext.current
    
    // Initialize osmdroid configuration
    remember {
        Configuration.getInstance().load(context, android.preference.PreferenceManager.getDefaultSharedPreferences(context))
        Configuration.getInstance().userAgentValue = context.packageName
        true
    }
    
    // State to track if we have performed the initial centering
    // State to track if we have performed the initial centering
    // State to track if we have performed the initial centering
    var hasCentered by remember { mutableStateOf(false) }
    // State for Travel Status Overlay expansion. Auto-expand if no role set.
    // Fixed: Removed (myRole) key so it doesn't auto-reset/collapse when role changes
    var isStatusExpanded by remember { mutableStateOf(myRole == "NONE") }
    
    // Peer Selection for Dialog
    var selectedPeer by remember { mutableStateOf<Peer?>(null) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false) // Disable default buttons to prevent accidental destination clicks
            controller.setZoom(15.0)
        }
    }
    
    // Track markers for reuse (avoids creating new Marker objects each frame)
    val peerMarkers = remember { mutableMapOf<String, Marker>() }
    val myMarkerObj = remember { Marker(mapView).apply { position = GeoPoint(0.0, 0.0) } }

    // Fixed: Added myRole as key so the listener captures the UPDATED role value
    val eventsOverlay = remember(myRole) {
        val receiver = object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                if (isStatusExpanded && myRole != "NONE") {
                    // Only auto-close on tap if we have a valid role
                    isStatusExpanded = false
                    return true
                }
                return false
            }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let { 
                    if (myRole == "NONE") {
                        Toast.makeText(context, "Please select Driver or Passenger first!", Toast.LENGTH_SHORT).show()
                        isStatusExpanded = true
                    } else {
                         // No manual waypoint mode button anymore, logic merged
                         // onAddWaypoint was used by that mode, now handled by long press
                         // But for clarity we just call the main handler
                         onMapLongClick(it)
                    }
                }
                return true
            }
        }
        MapEventsOverlay(receiver)
    }

    Box(modifier = modifier) {
        // Map View
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { map ->
              try {
                map.overlays.clear()
                map.overlays.add(0, eventsOverlay) // Add at 0 to be below markers

                // --- PICKUP SELECTION MODE ---
                val activeOffer = viewModel?.reviewingOffer
                if (activeOffer != null) {
                     // 1. Draw Driver's Route
                     if (activeOffer.routePoints.isNotEmpty()) {
                          val line = Polyline()
                          val routeGeoPoints = activeOffer.routePoints.map { GeoPoint(it.first, it.second) }
                          line.setPoints(routeGeoPoints)
                          line.outlinePaint.color = Color.MAGENTA
                          line.outlinePaint.strokeWidth = 8f
                          map.overlays.add(line)
                          
                          val pickupIdx = viewModel.reviewingPickupIndex
                          if (pickupIdx in activeOffer.routePoints.indices) {
                              val pLat = activeOffer.routePoints[pickupIdx].first
                              val pLon = activeOffer.routePoints[pickupIdx].second
                              val pickupGeo = GeoPoint(pLat, pLon)
                              
                              val pickupMarker = Marker(map)
                              pickupMarker.position = pickupGeo
                              pickupMarker.title = "📍 Selected Pickup"
                              pickupMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                              
                              val pickupBitmap = createTeardropMarkerBitmap("📍", Color.BLACK, Color.YELLOW, Color.BLACK)
                              pickupMarker.icon = BitmapDrawable(context.resources, pickupBitmap)
                              map.overlays.add(pickupMarker)
                              
                              if (!hasCentered) {
                                  map.controller.setCenter(pickupGeo)
                                  map.controller.setZoom(15.0)
                                  hasCentered = true
                              }
                          }
                     }
                     
                     if (myLocation != null) {
                         val myMarker = Marker(map)
                         myMarker.position = myLocation
                         myMarker.title = "You are here"
                         myMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                         val myBitmap = createTeardropMarkerBitmap("🙋", Color.BLACK, Color.GRAY, Color.BLACK, sizeDp = 40)
                         myMarker.icon = BitmapDrawable(context.resources, myBitmap)
                         map.overlays.add(myMarker)
                     }
                     
                     map.invalidate()
                     return@AndroidView
                }

                // --- NORMAL MAP MODE ---
                
                // Marker for "Me"
                if (myLocation != null) {
                    myMarkerObj.position = myLocation
                    myMarkerObj.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)

                    val startEmoji = when (myRole) {
                        "DRIVER" -> "🚗"
                        "PASSENGER" -> "🙋"
                        else -> "🏠"
                    }
                    myMarkerObj.title = "Start"
                    
                    val startBitmap = createTeardropMarkerBitmap(
                        text = startEmoji, 
                        outerColor = Color.BLACK, 
                        innerColor = Color.GREEN
                    )
                    myMarkerObj.icon = BitmapDrawable(context.resources, startBitmap)
                    
                    map.overlays.add(myMarkerObj)
                    
                    // Destination & Line for Me
                    if (myDestination != null || (viewModel != null && viewModel.myWaypoints.isNotEmpty())) {
                        viewModel?.myWaypoints?.forEachIndexed { index, wp ->
                            val wayMarker = Marker(map)
                            wayMarker.position = wp
                            wayMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            wayMarker.title = "Stop ${index + 1}"
                            
                            val numberBitmap = createTeardropMarkerBitmap(
                                text = "${index + 1}", 
                                outerColor = Color.BLACK, 
                                innerColor = Color.GREEN
                            )
                            wayMarker.icon = BitmapDrawable(context.resources, numberBitmap)
                            wayMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            
                            map.overlays.add(wayMarker)
                        }

                        if (myDestination != null) {
                            val destMarker = Marker(map)
                            destMarker.position = myDestination
                            destMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            destMarker.title = "Finish 🏁"
                            
                            val destBitmap = createTeardropMarkerBitmap(
                                text = "🏁", 
                                outerColor = Color.BLACK, 
                                innerColor = Color.GREEN
                            )
                            destMarker.icon = BitmapDrawable(context.resources, destBitmap)
                            
                            map.overlays.add(destMarker)
                        }
                        
                        if (viewModel?.roadRoute != null) {
                             val line = Polyline()
                             line.setPoints(viewModel.roadRoute)
                             line.outlinePaint.color = Color.BLUE
                             line.outlinePaint.strokeWidth = 5f
                             map.overlays.add(line)
                        } else if (myRole != "DRIVER") {
                            val points = mutableListOf<org.osmdroid.util.GeoPoint>()
                            if (myLocation != null) points.add(myLocation)
                            if (viewModel != null) {
                                points.addAll(viewModel.myWaypoints)
                            }
                            if (myDestination != null) points.add(myDestination)
                            
                            if (points.size > 1) {
                                val line = Polyline()
                                line.setPoints(points)
                                line.outlinePaint.color = Color.CYAN
                                line.outlinePaint.strokeWidth = 3f
                                map.overlays.add(line)
                            }
                        }
                    }

                    // Auto-center on first valid location
                    if (!hasCentered) {
                        map.controller.setCenter(myLocation)
                        map.controller.setZoom(13.0) 
                        hasCentered = true
                    }
                }

                // Markers for Peers
                val currentPeerIds = peers.map { it.publicKey }.toSet()
                peerMarkers.keys.retainAll(currentPeerIds)

                peers.forEach { peer ->
                    if (peer.latitude != null && peer.longitude != null) {
                        val newPos = GeoPoint(peer.latitude, peer.longitude)
                        val peerMarker = peerMarkers.getOrPut(peer.publicKey) {
                            Marker(map).apply {
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            }
                        }
                        peerMarker.position = newPos

                        val roleIconStr = if (peer.role == "DRIVER") "🚗" else if (peer.role == "PASSENGER") "🙋" else "👤"
                        val stars = "★".repeat(minOf(peer.reputationScore, 5))
                        peerMarker.title = "$roleIconStr ${peer.nickname} $stars\n${peer.role}"
                        peerMarker.snippet = peer.extraInfo

                        val letter = viewModel?.getRequestLetter(peer.publicKey)
                        val markerBitmap = if (peer.role == "PASSENGER" && letter != null) {
                            createTeardropMarkerBitmap(letter, Color.BLACK, Color.parseColor("#FFD600"), Color.BLACK)
                        } else if (peer.role == "DRIVER") {
                            createTeardropMarkerBitmap("🚗", Color.BLACK, Color.parseColor("#00BCD4"), Color.BLACK)
                        } else {
                            null
                        }
                        if (markerBitmap != null) {
                            peerMarker.icon = BitmapDrawable(context.resources, markerBitmap)
                        } else {
                            peerMarker.icon.mutate().setTint(Color.DKGRAY)
                        }
                        
                        peerMarker.setOnMarkerClickListener { _, _ ->
                            selectedPeer = peer
                            true
                        }
                        
                        map.overlays.add(peerMarker)
                        
                        if (peer.destLat != null && peer.destLon != null) {
                            val peerDest = GeoPoint(peer.destLat, peer.destLon)
                            val destMarker = Marker(map)
                            destMarker.position = peerDest
                            destMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            destMarker.title = "${peer.nickname}'s Destination 🏁"
                            if (peer.role == "DRIVER") {
                                destMarker.icon.mutate().setTint(Color.RED)
                            } else if (peer.role == "PASSENGER") {
                                destMarker.icon.mutate().setTint(Color.GREEN)
                            }
                            map.overlays.add(destMarker)
                        }
                    }
                }
                
                // Draw road route if available
                roadRoute?.let { route ->
                    if (route.size >= 2) {
                        val routeLine = Polyline()
                        routeLine.setPoints(route)
                        routeLine.outlinePaint.color = Color.CYAN
                        routeLine.outlinePaint.strokeWidth = 8f
                        map.overlays.add(routeLine)
                    }
                }
                
                // Highlight grid cells
                val gridsToDraw = mutableSetOf<String>()
                if (highlightedGrids != null) gridsToDraw.addAll(highlightedGrids)
                
                if (myRole == "PASSENGER" && myLocation != null) {
                    val radiusMeters = viewModel?.maxWalkingDistanceMeters?.toDouble() ?: 500.0
                    val currentGrid = RadioGridUtils.getGridId(myLocation.latitude, myLocation.longitude)
                    val candidates = RadioGridUtils.getNeighborGrids(currentGrid, 1)
                    
                    candidates.forEach { gridId ->
                        RadioGridUtils.getGridBounds(gridId)?.let { (sw, ne) ->
                            val clampedLat = myLocation.latitude.coerceIn(sw.latitude, ne.latitude)
                            val clampedLon = myLocation.longitude.coerceIn(sw.longitude, ne.longitude)
                            
                            val results = FloatArray(1)
                            Location.distanceBetween(myLocation.latitude, myLocation.longitude, clampedLat, clampedLon, results)
                            
                            if (results[0] <= radiusMeters) {
                                gridsToDraw.add(gridId)
                            }
                        }
                    }
                }

                gridsToDraw.forEach { gridId ->
                    val isCurrent = gridId == currentGridId
                    RadioGridUtils.getGridBounds(gridId)?.let { (sw, ne) ->
                        val polygon = Polygon()
                        polygon.points = listOf(
                            GeoPoint(sw.latitude, sw.longitude),
                            GeoPoint(sw.latitude, ne.longitude),
                            GeoPoint(ne.latitude, ne.longitude),
                            GeoPoint(ne.latitude, sw.longitude),
                            GeoPoint(sw.latitude, sw.longitude)
                        )
                        if (myRole == "PASSENGER") {
                             polygon.fillPaint.color = Color.argb(20, 0, 0, 255)
                             polygon.outlinePaint.color = Color.argb(50, 0, 0, 255)
                             polygon.outlinePaint.strokeWidth = 2f
                        } else {
                            if (isCurrent) {
                                polygon.fillPaint.color = Color.argb(60, 255, 165, 0)
                                polygon.outlinePaint.color = Color.argb(180, 255, 165, 0)
                                polygon.outlinePaint.strokeWidth = 4f
                            } else {
                                polygon.fillPaint.color = Color.argb(40, 106, 90, 205)
                                polygon.outlinePaint.color = Color.argb(120, 106, 90, 205)
                                polygon.outlinePaint.strokeWidth = 2f
                            }
                        }
                        map.overlays.add(polygon)
                    }
                }
                
                // Walking Radius Circles (Passenger Only)
                if (myRole == "PASSENGER") {
                    val radiusMeters = viewModel?.maxWalkingDistanceMeters?.toDouble() ?: 500.0
                    val dashEffect = DashPathEffect(floatArrayOf(20f, 20f), 0f)
                    
                    if (myLocation != null) {
                         val circlePoints = Polygon.pointsAsCircle(myLocation, radiusMeters)
                         val circle = Polygon()
                         circle.points = circlePoints
                         circle.fillPaint.color = Color.TRANSPARENT
                         circle.outlinePaint.color = Color.BLUE
                         circle.outlinePaint.strokeWidth = 5f
                         circle.outlinePaint.pathEffect = dashEffect
                         map.overlays.add(circle)
                    }
                    
                    if (myDestination != null) {
                         val circlePoints = Polygon.pointsAsCircle(myDestination, radiusMeters)
                         val circle = Polygon()
                         circle.points = circlePoints
                         circle.fillPaint.color = Color.TRANSPARENT
                         circle.outlinePaint.color = Color.BLUE
                         circle.outlinePaint.strokeWidth = 5f
                         circle.outlinePaint.pathEffect = dashEffect
                         map.overlays.add(circle)
                    }
                }
                
                // Draw Offer Markers (DHT)
                offers.forEach { offer ->
                    val offerPos = if (offer.driverCurrentLat != null && offer.driverCurrentLon != null) {
                        GeoPoint(offer.driverCurrentLat, offer.driverCurrentLon)
                    } else {
                        RadioGridUtils.getGridCenter(offer.gridId)?.let { GeoPoint(it.latitude, it.longitude) }
                    }

                    if (offerPos != null) {
                        val offerMarker = Marker(map)
                        offerMarker.position = offerPos
                        offerMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        
                        val isPrecise = offer.driverCurrentLat != null
                        val iconStr = if (isPrecise) "🚗" else "📡"
                        
                        offerMarker.title = "$iconStr Offer: ${offer.senderNickname}"
                        offerMarker.snippet = "RIDE OFFER: ${offer.senderNickname} (${offer.availableSeats} seats)"
                        
                        offerMarker.icon.mutate().setTint(Color.parseColor("#4CAF50"))
                        
                        offerMarker.setOnMarkerClickListener { marker, _ ->
                             marker.showInfoWindow()
                             true
                        }
                        
                        map.overlays.add(offerMarker)
                    }
                }

                map.invalidate()
              } catch (e: Exception) {
                  Log.e("MapComposable", "Error in map update", e)
              }
            }
        )
        
        // --- TOP OVERLAY: Status & Broadcasting ---
        // Hide during pickup review
        if (viewModel?.reviewingOffer == null) {
            TransportModeOverlay(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
                    .fillMaxWidth(0.95f),
                myRole = myRole,
                mySeats = mySeats,
                maxWalkingDistance = viewModel?.maxWalkingDistanceMeters ?: 500,
                isBroadcasting = isBroadcasting,
                myInfo = viewModel?.myInfo ?: "",
                myDestination = myDestination,
                expanded = isStatusExpanded,
                onExpandedChange = { isStatusExpanded = it },
                onRoleChange = { viewModel?.myRole = it },
                onSeatsChange = { viewModel?.mySeats = it },
                onWalkingDistanceChange = { viewModel?.maxWalkingDistanceMeters = it },
                onInfoChange = { viewModel?.myInfo = it },
                onToggleBroadcasting = { 
                    onToggleBroadcasting(it)
                    if (it) isStatusExpanded = false
                },
                onBroadcastClick = { viewModel?.broadcastStatus(viewModel.myNickname) }
            )
        }

        // Bottom Controls Overlay
        // Hide during pickup review
        if (viewModel?.reviewingOffer == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Zoom
                Row {
                    SmallFloatingActionButton(
                        onClick = { mapView.controller.zoomIn() },
                        containerColor = ComposeColor.White.copy(alpha = 0.9f)
                    ) { Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                    Spacer(modifier = Modifier.width(8.dp))
                    SmallFloatingActionButton(
                        onClick = { mapView.controller.zoomOut() },
                        containerColor = ComposeColor.White.copy(alpha = 0.9f)
                    ) { Text("-", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                }

                // Info Strip
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = ComposeColor.Black.copy(alpha = 0.7f),
                    contentColor = ComposeColor.White,
                    modifier = Modifier.padding(horizontal = 8.dp).weight(1f)
                ) {
                    Text(
                        text = when {
                            myRole == "NONE" -> "⚠️ Please select Role"
                            myDestination == null -> "🏁 Long-press to set destination"
                            myRole == "DRIVER" && roadRoute == null -> "📍 Long-press to add stops"
                            roadRoute != null -> "🛣️ Route Active"
                            else -> "🏁 Route set"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(8.dp),
                        textAlign = TextAlign.Center
                    )
                }

                // Single Undo Button - removes last waypoint, then destination
                if (myDestination != null || (viewModel != null && viewModel.myWaypoints.isNotEmpty())) {
                    SmallFloatingActionButton(
                        onClick = { onClearWaypoint() },
                        containerColor = ComposeColor.White.copy(alpha = 0.9f)
                    ) { 
                        Text("↩️", fontSize = 16.sp)
                    }
                }
            }
        }
        }
        
        // --- BOTTOM PANEL OVERRIDES ---
        if (viewModel?.reviewingOffer != null) {
            val offer = viewModel.reviewingOffer!!
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = ComposeColor.White,
                shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Fine-tune Pickup Location",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Move the 📍 along the driver's route to a safe spot (like a bus stop).",
                        style = MaterialTheme.typography.bodySmall,
                        color = ComposeColor.Gray
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Nudge Backward
                        FilledTonalButton(
                            onClick = {
                                if (viewModel.reviewingPickupIndex > 0) {
                                    viewModel.reviewingPickupIndex--
                                }
                            }
                        ) {
                            Text("◀ Nudge", fontSize = 16.sp)
                        }
                        
                        // Nudge Forward
                        FilledTonalButton(
                            onClick = {
                                if (viewModel.reviewingPickupIndex < offer.routePoints.size - 1) {
                                    viewModel.reviewingPickupIndex++
                                }
                            }
                        ) {
                            Text("Nudge ▶", fontSize = 16.sp)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = {
                                viewModel.reviewingOffer = null
                                viewModel.reviewingPickupIndex = -1
                                hasCentered = false // allow re-centering next time
                            }
                        ) {
                            Text("Cancel", color = ComposeColor.Red)
                        }
                        
                        Button(
                            onClick = {
                                val pickupIdx = viewModel.reviewingPickupIndex
                                if (pickupIdx in offer.routePoints.indices) {
                                    val finalLat = offer.routePoints[pickupIdx].first
                                    val finalLon = offer.routePoints[pickupIdx].second
                                    
                                     val requestMessage = buildString {
                                         append("Hi ${offer.senderNickname}, I'd like a ride! 🙋")
                                         append("\n📍 Location: $finalLat, $finalLon")
                                         if (viewModel.myDestLat != null && viewModel.myDestLon != null) {
                                             append("\n🏁 Destination: ${viewModel.myDestLat}, ${viewModel.myDestLon}")
                                         }
                                     }
                                     viewModel.sendMessage(
                                         Peer(offer.senderOnion, offer.senderNickname, offer.senderOnion, isTrusted=true, lastSeenTimestamp=System.currentTimeMillis()), 
                                         requestMessage, 
                                         viewModel.myNickname,
                                         type = "ride_request" 
                                     )
                                     Toast.makeText(context, "Ride Request Sent!", Toast.LENGTH_SHORT).show()
                                     
                                     // cleanup
                                     viewModel.reviewingOffer = null
                                     viewModel.reviewingPickupIndex = -1
                                     hasCentered = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF4CAF50))
                        ) {
                            Text("Confirm & Request Ride 📨")
                        }
                    }
                }
            }
        }
    }

    if (selectedPeer != null) {
        PeerActionDialog(
            peer = selectedPeer!!,
            myRole = myRole,
            onDismiss = { selectedPeer = null },
            onAccept = { 
                viewModel?.acceptRide(it)
                Toast.makeText(context, "Accepted ${it.nickname}!", Toast.LENGTH_SHORT).show()
                selectedPeer = null
            },
            onDecline = {
                viewModel?.declineRide(it)
                Toast.makeText(context, "Declined ${it.nickname}", Toast.LENGTH_SHORT).show()
                selectedPeer = null
            }
        )
    }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
        }
    }
}

@Composable
fun PeerActionDialog(
    peer: Peer,
    myRole: String,
    onDismiss: () -> Unit,
    onAccept: (Peer) -> Unit,
    onDecline: (Peer) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = if(peer.role == "DRIVER") "🚗" else "🙋", fontSize = 24.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = peer.nickname, fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Reputation: ", fontWeight = FontWeight.Bold)
                    Text("★".repeat(minOf(peer.reputationScore, 5)), color = ComposeColor(0xFFFFD700))
                    Text(" (${peer.reputationScore})", color = ComposeColor.Gray)
                }
                Spacer(modifier = Modifier.height(4.dp))
                if (peer.extraInfo.isNotEmpty()) {
                    Text("Info: ${peer.extraInfo}")
                    Spacer(modifier = Modifier.height(4.dp))
                }
                
                if (myRole == "DRIVER" && peer.role == "PASSENGER") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Do you want to pick up this passenger?", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                }
            }
        },
        confirmButton = {
            if (myRole == "DRIVER" && peer.role == "PASSENGER") {
                Button(
                    onClick = { onAccept(peer) },
                    colors = ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF43A047))
                ) {
                    Text("ACCEPT ✅")
                }
            } else {
                 TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = {
            if (myRole == "DRIVER" && peer.role == "PASSENGER") {
                OutlinedButton(
                    onClick = { onDecline(peer) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ComposeColor.Red)
                ) {
                    Text("DECLINE ❌")
                }
            }
        }
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportModeOverlay(
    modifier: Modifier = Modifier,
    myRole: String,
    mySeats: Int,
    maxWalkingDistance: Int = 500,
    isBroadcasting: Boolean,
    myInfo: String,
    myDestination: GeoPoint?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onRoleChange: (String) -> Unit,
    onSeatsChange: (Int) -> Unit,
    onWalkingDistanceChange: (Int) -> Unit = {},
    onInfoChange: (String) -> Unit,
    onToggleBroadcasting: (Boolean) -> Unit,
    onBroadcastClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .clickable { if (!expanded) onExpandedChange(true) }, // Click to expand
        shape = MaterialTheme.shapes.medium,
        color = ComposeColor.White.copy(alpha = 0.95f),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth()
        ) {
            // Header Row (Always Visible-ish) - In compact mode this IS the view
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = when (myRole) {
                                "DRIVER" -> "🚗 Driver"
                                "PASSENGER" -> "🙋 Passenger"
                                else -> "😐 Chill"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = ComposeColor.Black
                        )
                        if (!expanded) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("(Tap to Edit)", fontSize = 10.sp, color = ComposeColor.Gray)
                        }
                    }
                    
                    if (!expanded) {
                        if (myRole == "DRIVER") {
                            Text(text = "Seats: $mySeats", style = MaterialTheme.typography.labelSmall, color = ComposeColor.Gray)
                        }
                        if (myRole == "PASSENGER") {
                            Text(text = "🚶 Max: ${maxWalkingDistance}m", style = MaterialTheme.typography.labelSmall, color = ComposeColor.Gray)
                        }
                        Text(
                            text = if (myDestination != null) "Route: set 📍" else "Route: NOT set ❌",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (myDestination != null) ComposeColor(0xFF2E7D32) else ComposeColor.Red
                        )
                    }
                }

                // Broadcast Switch & Indicator
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isBroadcasting) "Broadcast LIVE 📡" else "Broadcast is OFFLINE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isBroadcasting) ComposeColor.Red else ComposeColor.Gray
                    )
                    Switch(
                        checked = isBroadcasting,
                        onCheckedChange = onToggleBroadcasting,
                        enabled = myRole != "NONE" && myDestination != null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ComposeColor.Red,
                            checkedTrackColor = ComposeColor.Red.copy(alpha = 0.5f)
                        )
                    )
                }
            }

            // EXPANDED CONTENT
            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Divider()
                Spacer(modifier = Modifier.height(12.dp))

                // Role Selection
                Text("Select Role:", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    FilterChip(
                        selected = myRole == "DRIVER",
                        onClick = { onRoleChange("DRIVER") },
                        label = { Text("🚗 Driver") }
                    )
                    FilterChip(
                        selected = myRole == "PASSENGER",
                        onClick = { onRoleChange("PASSENGER") },
                        label = { Text("🙋 Passenger") }
                    )
                }

                if (myRole == "DRIVER") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Available Seats: $mySeats")
                    Slider(
                        value = mySeats.toFloat(),
                        onValueChange = { onSeatsChange(it.toInt()) },
                        valueRange = 0f..8f,
                        steps = 7
                    )
                }

                if (myRole == "PASSENGER") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("\uD83D\uDEB6 Max kävelymatka: ${maxWalkingDistance}m")
                    Slider(
                        value = maxWalkingDistance.toFloat(),
                        onValueChange = { onWalkingDistanceChange(it.toInt()) },
                        valueRange = 100f..2000f,
                        steps = 18  // 100m steps
                    )
                }

                if (myRole != "NONE") {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = myInfo,
                        onValueChange = onInfoChange,
                        label = { Text(if (myRole == "DRIVER") "Route Note" else "Note") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                // Broadcast button removed as requested. Switch handles it.
                
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = { onExpandedChange(false) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Done / Collapse 🔽")
                }
            }
        }
    }
}
