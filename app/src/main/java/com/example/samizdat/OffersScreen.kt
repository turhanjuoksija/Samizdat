package com.example.samizdat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun OffersScreen(viewModel: PeersViewModel) {
    val myRole = viewModel.myRole
    
    if (myRole == "DRIVER") {
        DriverDashboard(viewModel)
    } else {
        PassengerOffers(viewModel)
    }
}

@Composable
fun DriverDashboard(viewModel: PeersViewModel) {
    val requests by viewModel.incomingRequests.collectAsState(initial = emptyList())
    
    // Update letter assignments whenever request list changes
    LaunchedEffect(requests) {
        viewModel.updateRequestLetters(requests)
    }
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            text = "Driver Dashboard 🚖", 
            style = MaterialTheme.typography.titleLarge, 
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Incoming Ride Requests", 
            style = MaterialTheme.typography.labelMedium,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        if (requests.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No ride requests yet. Keep broadcasting! 📡", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(requests.size) { index ->
                    val msg = requests[index]
                    val letter = ('A' + index).toString()
                    RequestCard(msg, viewModel, letter)
                }
            }
        }
    }
}

@Composable
fun PassengerOffers(viewModel: PeersViewModel) {
    val offers = viewModel.getFilteredOffers()
    val allOffers = viewModel.dhtManager.getActiveOffers()
    val peers by viewModel.storedPeers.collectAsState(initial = emptyList())
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Find a Ride 🙋\u200D♂️", 
                style = MaterialTheme.typography.titleLarge, 
                fontWeight = FontWeight.Bold
            )

        }
        
        // Passenger info bar
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            shape = MaterialTheme.shapes.small,
            color = Color(0xFFE3F2FD),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "🚶 Max Walk: ${viewModel.maxWalkingDistanceMeters}m",
                    style = MaterialTheme.typography.labelMedium
                )
                if (allOffers.size != offers.size) {
                    Text(
                        text = "Filtered: ${offers.size}/${allOffers.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (offers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No matching offers found.", color = Color.Gray)
                    if (viewModel.myDestGridId == null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("📍 Set your destination on the Map!", 
                             color = MaterialTheme.colorScheme.primary,
                             style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(offers) { offer ->
                    val knownPeer = peers.find { it.publicKey == offer.senderOnion || it.onion == offer.senderOnion }
                    // FIX: Check for invalid direction (walk == -1)
                    val (walkToPickup, _) = viewModel.calculateWalkDistances(offer)
                    
                    if (walkToPickup != -1) {
                         OfferCard(offer, knownPeer, viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun RequestCard(msg: ChatMessage, viewModel: PeersViewModel, letter: String = "?") {
    val context = androidx.compose.ui.platform.LocalContext.current
    val peers by viewModel.storedPeers.collectAsState(initial = emptyList())
    val passengerPeer = remember(peers, msg.peerPublicKey) {
        peers.find { it.publicKey == msg.peerPublicKey }
    }
    
    // Parse passenger location from message content
    // Format: "📍 Location: lat, lon" and "🏁 Destination: lat, lon"
    val passengerLocation = remember(msg.content) {
        val regex = """📍 Location: ([\d.]+), ([\d.]+)""".toRegex()
        regex.find(msg.content)?.let { 
            Pair(it.groupValues[1].toDoubleOrNull(), it.groupValues[2].toDoubleOrNull())
        }
    }
    
    val passengerDestination = remember(msg.content) {
        val regex = """🏁 Destination: ([\d.]+), ([\d.]+)""".toRegex()
        regex.find(msg.content)?.let { 
            Pair(it.groupValues[1].toDoubleOrNull(), it.groupValues[2].toDoubleOrNull())
        }
    }

    val isOnMap = remember(passengerPeer) {
        passengerPeer != null && passengerPeer.latitude != null && passengerPeer.longitude != null
    }
    val isAccepted = viewModel.isRequestAccepted(msg.id)
    val isMapped = viewModel.isRequestMapped(msg.id)

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAccepted) Color(0xFFE8F5E9)
                else if (isMapped) Color(0xFFE3F2FD) // Light Blue for "viewed on map"
                else if (isOnMap) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.tertiaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        text = if (isAccepted) "$letter — Accepted ✅" else "$letter — Ride Request 🔔", 
                        fontWeight = FontWeight.Bold, 
                        color = if (isAccepted) Color(0xFF2E7D32)
                            else if (isOnMap) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onTertiaryContainer
                    )
                     if (passengerPeer != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(passengerPeer.nickname, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "★".repeat(minOf(passengerPeer.reputationScore, 5)),
                                color = Color(0xFFFFD700),
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("(${passengerPeer.reputationScore})", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
                Text(convertTimestamp(msg.timestamp), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            // Show message content (first line only, without location data)
            val mainMessage = msg.content.lines().firstOrNull() ?: msg.content
            Text(mainMessage, style = MaterialTheme.typography.bodyLarge)
            
            Spacer(modifier = Modifier.height(16.dp))
            if (isAccepted) {
                // Accepted state: only show Cancel button
                Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                        onClick = {
                             viewModel.declineRideRequest(msg)
                             viewModel.dismissRequest(msg)
                             android.widget.Toast.makeText(context, "Cancelled", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("CANCEL ❌")
                    }
                }
            } else {
                // Normal state: Map + Accept + Decline buttons
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // MAP BUTTON (Icon only, toggles state)
                    if (passengerLocation != null && passengerLocation.first != null) {
                        OutlinedButton(
                            onClick = {
                                if (passengerLocation.first != null && passengerLocation.second != null) {
                                    viewModel.savePeer(
                                        lastKnownIp = msg.peerPublicKey,
                                        nickname = passengerPeer?.nickname ?: "Passenger",
                                        publicKey = msg.peerPublicKey,
                                        role = "PASSENGER",
                                        lat = passengerLocation.first,
                                        lon = passengerLocation.second,
                                        dLat = passengerDestination?.first,
                                        dLon = passengerDestination?.second
                                    )
                                    viewModel.toggleRequestMapped(msg.id)
                                    if (!isMapped) {
                                         android.widget.Toast.makeText(context, "Shown on Map 🗺️", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.width(48.dp) // Square-ish
                        ) {
                            Text("🗺️", fontSize = 20.sp)
                        }
                    }
                    
                    // ACCEPT BUTTON (Main action, takes remaining width)
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF43A047)),
                        onClick = {
                            // Also update location on Accept
                            if (passengerLocation != null && passengerLocation.first != null) {
                                 viewModel.savePeer(
                                    lastKnownIp = msg.peerPublicKey,
                                    nickname = passengerPeer?.nickname ?: "Passenger",
                                    publicKey = msg.peerPublicKey,
                                    role = "PASSENGER",
                                    lat = passengerLocation.first,
                                    lon = passengerLocation.second,
                                    dLat = passengerDestination?.first,
                                    dLon = passengerDestination?.second
                                )
                            }
                            viewModel.acceptRideRequest(msg)
                            android.widget.Toast.makeText(context, "Accepted: ${msg.peerPublicKey.take(8)}...", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("ACCEPT ✅", maxLines = 1)
                    }

                    // DECLINE BUTTON (Icon only)
                    OutlinedButton(
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                        onClick = {
                             viewModel.declineRideRequest(msg)
                             viewModel.dismissRequest(msg)
                             android.widget.Toast.makeText(context, "Declined", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.width(48.dp) // Square-ish
                    ) {
                        Text("❌", fontSize = 20.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun OfferCard(offer: KademliaNode.GridMessage, knownPeer: Peer?, viewModel: PeersViewModel) {
    var showDetails by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    
    // Calculate walking distances
    val (walkToPickup, walkFromDropoff) = viewModel.calculateWalkDistances(offer)

    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.clickable { showDetails = true }
    ) {
        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            // Header: Driver name, reputation, timestamp
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (knownPeer != null) knownPeer.nickname else offer.senderNickname, 
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (knownPeer != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "★".repeat(minOf(knownPeer.reputationScore, 5)),
                            color = Color(0xFFFFD700),
                            fontSize = 14.sp
                        )
                    }
                    // Available seats badge
                    if (offer.availableSeats > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = Color(0xFF4CAF50),
                            contentColor = Color.White
                        ) {
                            Text(
                                text = "🪑 ${offer.availableSeats}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                Text(
                    text = convertTimestamp(offer.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Walking distances info
            if (walkToPickup > 0 || walkFromDropoff > 0) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = Color(0xFFE3F2FD),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🚶→🚗", fontSize = 16.sp)
                            Text(
                                text = "${walkToPickup}m",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text("➜", fontSize = 20.sp, color = Color.Gray)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🚗→🏁", fontSize = 16.sp)
                            Text(
                                text = "${walkFromDropoff}m",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            // Route info
            Text(text = offer.content, style = MaterialTheme.typography.bodyMedium)
            
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                // Route coverage
                if (offer.routeGrids.isNotEmpty()) {
                    Text(
                        text = "📍 ${offer.routeGrids.size} grids", 
                        style = MaterialTheme.typography.labelSmall, 
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Text(
                        text = "Grid: ${offer.gridId}", 
                        style = MaterialTheme.typography.labelSmall, 
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (knownPeer != null && knownPeer.isTrusted) {
                    Text("Trusted ✅", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
                }
                
                TextButton(onClick = { showDetails = true }) {
                    Text("VIEW", fontSize = 12.sp)
                }
            }
        }
    }

    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Ride Offer Details") },
            text = {
                Column {
                    Text("Driver: ${offer.senderNickname}")
                    if (knownPeer != null) {
                        Text("Reputation: ${knownPeer.reputationScore} Vouch(es)")
                        Text("Role: ${knownPeer.role} (${knownPeer.seats} seats)")
                        if (knownPeer.extraInfo.isNotEmpty()) Text("Info: ${knownPeer.extraInfo}")
                    } else {
                        Text("⚠️ Unknown / Untrusted Driver", color = Color.Red)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Walking distances
                    if (walkToPickup > 0 || walkFromDropoff > 0) {
                        Text("🚶 Walk to pickup: ${walkToPickup}m")
                        Text("🚶 Walk from dropoff: ${walkFromDropoff}m")
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // Available seats
                    Text("🪑 Available seats: ${offer.availableSeats}")
                    
                    // Driver location if available
                    if (offer.driverCurrentLat != null && offer.driverCurrentLon != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "📍 Driver location available",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Message: ${offer.content}")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                         // Send ride request message with passenger location
                         val requestMessage = buildString {
                             append("Hi ${offer.senderNickname}, I'd like a ride! 🙋")
                             // Include passenger location in message
                             if (viewModel.myLatitude != null && viewModel.myLongitude != null) {
                                 append("\n📍 Location: ${viewModel.myLatitude}, ${viewModel.myLongitude}")
                             }
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
                         android.widget.Toast.makeText(context, "Request sent!", android.widget.Toast.LENGTH_SHORT).show()
                         showDetails = false
                    }
                ) {
                    Text("Request Ride 📨")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDetails = false }) { Text("Close") }
            }
        )
    }
}

private fun convertTimestamp(ts: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}
