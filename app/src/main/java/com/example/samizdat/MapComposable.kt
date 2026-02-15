package com.example.samizdat

import android.graphics.drawable.Drawable
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
                map.overlays.clear()
                map.overlays.add(0, eventsOverlay) // Add at 0 to be below markers

                // Marker for "Me"
                if (myLocation != null) {
                    val myMarker = Marker(map)
                    myMarker.position = myLocation
                    myMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    
                    // Custom emoji-based icon for start position
                    // Custom emoji-based icon for start position
                    val startEmoji = when (myRole) {
                        "DRIVER" -> "🚗"
                        "PASSENGER" -> "🙋"
                        else -> "🏠"
                    }
                    myMarker.title = "Start"
                    
                    // Create custom teardrop icon (Black Outer, Green Inner)
                    val startBitmap = createTeardropMarkerBitmap(
                        text = startEmoji, 
                        outerColor = Color.BLACK, 
                        innerColor = Color.GREEN // User requested uniform style
                    )
                    myMarker.icon = BitmapDrawable(context.resources, startBitmap)
                    myMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    
                    map.overlays.add(myMarker)
                    
                    // Destination & Line for Me
                    if (myDestination != null || (viewModel != null && viewModel.myWaypoints.isNotEmpty())) {
                         // Draw Waypoints with numbered bubbles
                        viewModel?.myWaypoints?.forEachIndexed { index, wp ->
                            val wayMarker = Marker(map)
                            wayMarker.position = wp
                            wayMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            wayMarker.title = "Stop ${index + 1}"
                            
                            // Create numbered bubble icon
                            // Create numbered teardrop icon
                            val numberBitmap = createTeardropMarkerBitmap(
                                text = "${index + 1}", 
                                outerColor = Color.BLACK, 
                                innerColor = Color.GREEN
                            )
                            wayMarker.icon = BitmapDrawable(context.resources, numberBitmap)
                            // Anchor bottom center because of the teardrop tail
                            wayMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            
                            map.overlays.add(wayMarker)
                        }

                        if (myDestination != null) {
                            val destMarker = Marker(map)
                            destMarker.position = myDestination
                            destMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            destMarker.title = "Finish 🏁"
                            
                            // Use consistent Teardrop style for Destination to avoid overlap/mismatch
                            val destBitmap = createTeardropMarkerBitmap(
                                text = "🏁", 
                                outerColor = Color.BLACK, 
                                innerColor = Color.GREEN
                            )
                            destMarker.icon = BitmapDrawable(context.resources, destBitmap)
                            
                            map.overlays.add(destMarker)
                        }
                        
                        // Draw Polyline (Route)
                        if (viewModel?.roadRoute != null) {
                             val line = Polyline()
                             line.setPoints(viewModel.roadRoute)
                             line.outlinePaint.color = Color.BLUE
                             line.outlinePaint.strokeWidth = 5f
                             map.overlays.add(line)
                        } else if (myRole != "DRIVER") {
                            // Fallback straight lines ONLY for Passengers (not Drivers)
                            val points = mutableListOf<org.osmdroid.util.GeoPoint>()
                            if (myLocation != null) points.add(myLocation)
                            if (viewModel != null) {
                                points.addAll(viewModel.myWaypoints)
                            }
                            if (myDestination != null) points.add(myDestination)
                            
                            if (points.size > 1) {
                                val line = Polyline()
                                line.setPoints(points)
                                line.outlinePaint.color = Color.CYAN // Cyan for direct line
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
                peers.forEach { peer ->
                    if (peer.latitude != null && peer.longitude != null) {
                        val peerPos = GeoPoint(peer.latitude, peer.longitude)
                        val peerMarker = Marker(map)
                        peerMarker.position = peerPos
                        peerMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        val roleIconStr = if (peer.role == "DRIVER") "🚗" else if (peer.role == "PASSENGER") "🙋" else "👤"
                        val stars = "★".repeat(minOf(peer.reputationScore, 5))
                        peerMarker.title = "$roleIconStr ${peer.nickname} $stars\n${peer.role}"
                        peerMarker.snippet = peer.extraInfo
                        peerMarker.icon.mutate().setTint(Color.DKGRAY)
                        
                        // Handle marker click to show details/actions
                        peerMarker.setOnMarkerClickListener { marker, mapView ->
                            selectedPeer = peer
                            true // Consume event so InfoWindow doesn't open (we show our own dialog)
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
                
                // Draw road route if available (thicker cyan line)
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
                // Logic:
                // 1. If we are PASSENGER, we show grids that intersect with our Walking Distance Circle.
                // 2. Otherwise/Additionally, we show explicitly highlighted grids (e.g. from debug or other logic).
                
                val gridsToDraw = mutableSetOf<String>()
                if (highlightedGrids != null) gridsToDraw.addAll(highlightedGrids)
                
                // Passenger "Scanning" Logic
                if (myRole == "PASSENGER" && myLocation != null) {
                    val radiusMeters = viewModel?.maxWalkingDistanceMeters?.toDouble() ?: 500.0
                    val currentGrid = RadioGridUtils.getGridId(myLocation.latitude, myLocation.longitude)
                    // Get 3x3 neighbors (radius 1 grid step is ~2km, plenty for <2km walking)
                    val candidates = RadioGridUtils.getNeighborGrids(currentGrid, 1)
                    
                    candidates.forEach { gridId ->
                        RadioGridUtils.getGridBounds(gridId)?.let { (sw, ne) ->
                            // Check intersection between Grid Rectangle and Walking Circle
                            // Simple clamp approach to find closest point on rect to circle center
                            val latMin = sw.latitude
                            val latMax = ne.latitude
                            val lonMin = sw.longitude
                            val lonMax = ne.longitude
                            
                            val clampedLat = myLocation.latitude.coerceIn(latMin, latMax)
                            val clampedLon = myLocation.longitude.coerceIn(lonMin, lonMax)
                            
                            val results = FloatArray(1)
                            Location.distanceBetween(myLocation.latitude, myLocation.longitude, clampedLat, clampedLon, results)
                            val distanceToRect = results[0]
                            
                            if (distanceToRect <= radiusMeters) {
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
                        // Style for "Scanned" grids
                        if (myRole == "PASSENGER") {
                             polygon.fillPaint.color = Color.argb(20, 0, 0, 255) // Very light blue fill
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
                    
                    // 1. Circle around My Location
                    if (myLocation != null) {
                         val circlePoints = Polygon.pointsAsCircle(myLocation, radiusMeters)
                         val circle = Polygon()
                         circle.points = circlePoints
                         circle.fillPaint.color = Color.TRANSPARENT
                         circle.outlinePaint.color = Color.BLUE // Dark Blue as requested
                         circle.outlinePaint.strokeWidth = 5f
                         circle.outlinePaint.pathEffect = dashEffect
                         map.overlays.add(circle)
                    }
                    
                    // 2. Circle around Destination
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
                        
                        // Green for Offers
                        offerMarker.icon.mutate().setTint(Color.parseColor("#4CAF50"))
                        
                        // Handle marker click to show details/actions
                        offerMarker.setOnMarkerClickListener { marker, mapView ->
                             // Could show dialog here too, but for now standard InfoWindow is fine
                             marker.showInfoWindow()
                             true
                        }
                        
                        map.overlays.add(offerMarker)
                    }
                }

                map.invalidate()
            }
        )
        
        // --- TOP OVERLAY: Status & Broadcasting ---
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

        // Bottom Controls Overlay
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
        onDispose { mapView.onPause() }
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
