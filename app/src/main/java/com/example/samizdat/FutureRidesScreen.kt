package com.example.samizdat

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun FutureRidesScreen(
    viewModel: FutureRidesViewModel, 
    peersViewModel: PeersViewModel,
) {
    val context = LocalContext.current
    val myIntents by viewModel.activeIntents.collectAsState()
    
    // Discovered future offers (filtered from DHT)
    val allOffers = peersViewModel.dhtManager.gridOffers
    val futureOffers = allOffers.filter { 
        it.departureTime != null && it.departureTime > System.currentTimeMillis()
    }.sortedBy { it.departureTime }

    var showCreateDialog by remember { mutableStateOf(false) }

    // Track dismissed offers locally (resets on app restart)
    val dismissedOffers = remember { mutableStateListOf<String>() }

    // Load saved peers for reputation lookup
    val savedPeers by peersViewModel.storedPeers.collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // --- Header ---
        Text(
            "Future Rides 🗓️", 
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // --- My Schedule Section ---
        Text("My Schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (myIntents.isEmpty()) {
            Text("No scheduled rides.", color = Color.Gray, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                items(myIntents) { intent ->
                    RideIntentCard(intent, onDelete = { viewModel.deleteIntent(intent) })
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { 
                if (peersViewModel.routeManager.hasDestination()) {
                    showCreateDialog = true 
                } else {
                    Toast.makeText(context, "Please set a route on the Map first!", Toast.LENGTH_LONG).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("📅 Schedule Current Route")
        }

        Divider(modifier = Modifier.padding(vertical = 16.dp))

        // --- Discovered Offers Section ---
        Text("Future Offers on Route", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "(Matches your current Map route)", 
            style = MaterialTheme.typography.bodySmall, 
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(8.dp))
        
        // Filter out dismissed offers
        val visibleOffers = futureOffers.filter { offer ->
            val key = "${offer.senderOnion}_${offer.timestamp}"
            key !in dismissedOffers
        }

        if (visibleOffers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No future rides found nearby.", color = Color.Gray)
            }
        } else {
            LazyColumn {
                items(visibleOffers) { offer ->
                    FutureOfferCard(
                        offer = offer,
                        peersViewModel = peersViewModel,
                        savedPeers = savedPeers,
                        onDismiss = {
                            dismissedOffers.add("${offer.senderOnion}_${offer.timestamp}")
                        }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateRideDialog(
            myRole = peersViewModel.myRole,
            onDismiss = { showCreateDialog = false },
            onConfirm = { date ->
                val role = peersViewModel.myRole
                val type = if (role == "DRIVER") "OFFER" else "REQUEST"
                val rm = peersViewModel.routeManager
                
                if (rm.myLatitude != null && rm.myDestLat != null) {
                    viewModel.createIntent(
                        type = type,
                        originLat = rm.myLatitude!!,
                        originLon = rm.myLongitude!!,
                        destLat = rm.myDestLat!!,
                        destLon = rm.myDestLon!!,
                        departureTime = date,
                        onSuccess = { showCreateDialog = false },
                        onError = { err -> Toast.makeText(context, err, Toast.LENGTH_SHORT).show() }
                    )
                }
            }
        )
    }
}

@Composable
fun RideIntentCard(intent: RideIntent, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (intent.type == "OFFER") "🚗 Driver Offer" else "🙋 Passenger Request",
                    fontWeight = FontWeight.Bold
                )
                Text("🛫 Departure: ${formatDate(intent.departureTime)}")
            }
            IconButton(onClick = onDelete) {
                Text("❌", fontSize = 18.sp)
            }
        }
    }
}

@Composable
fun FutureOfferCard(
    offer: KademliaNode.GridMessage,
    peersViewModel: PeersViewModel,
    savedPeers: List<Peer>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    // Lookup reputation
    val knownPeer = savedPeers.find { it.onion == offer.senderOnion || it.publicKey == offer.senderOnion }
    val reputationScore = knownPeer?.reputationScore ?: 0

    // Calculate freshness
    val ageMinutes = (System.currentTimeMillis() - offer.timestamp) / 60000
    val freshnessText = when {
        ageMinutes < 5 -> "Online now"
        ageMinutes < 60 -> "${ageMinutes} min ago"
        ageMinutes < 1440 -> "${ageMinutes / 60}h ago"
        else -> "${ageMinutes / 1440}d ago"
    }
    val freshnessColor = when {
        ageMinutes < 5 -> Color(0xFF4CAF50)   // Green
        ageMinutes < 15 -> Color(0xFFFFC107)   // Yellow
        else -> Color.Gray
    }
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Top row: name + freshness + dismiss
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(offer.senderNickname, fontWeight = FontWeight.Bold)
                    // Reputation — darker color for readability
                    Text(
                        "★ $reputationScore Vouch(es)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB8860B) // DarkGoldenrod — readable
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(freshnessText, style = MaterialTheme.typography.bodySmall, color = freshnessColor)
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Text("❌", fontSize = 14.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            
            // Departure time
            Text(
                "🛫 Departure: ${offer.departureTime?.let { formatDate(it) } ?: "???"}",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            // Seats
            if (offer.availableSeats > 0) {
                Text("💺 ${offer.availableSeats} seats available")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Action buttons row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Quick interest
                OutlinedButton(
                    onClick = {
                        val dummyPeer = Peer(
                            publicKey = offer.senderOnion,
                            nickname = offer.senderNickname,
                            lastKnownIp = offer.senderOnion,
                            onion = offer.senderOnion,
                            isTrusted = false,
                            lastSeenTimestamp = System.currentTimeMillis()
                        )
                        val msg = "👍 I'm interested in your trip on ${offer.departureTime?.let { formatDate(it) }}!"
                        peersViewModel.sendMessage(dummyPeer, msg, peersViewModel.myNickname)
                        Toast.makeText(context, "Interest sent!", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("👍 Interested")
                }
                
                // Full chat
                Button(
                    onClick = {
                        val dummyPeer = Peer(
                            publicKey = offer.senderOnion,
                            nickname = offer.senderNickname,
                            lastKnownIp = offer.senderOnion,
                            onion = offer.senderOnion,
                            isTrusted = false,
                            lastSeenTimestamp = System.currentTimeMillis()
                        )
                        val inquiry = "Hi! I saw your trip on ${offer.departureTime?.let { formatDate(it) }}. Is it still available?"
                        peersViewModel.sendMessage(dummyPeer, inquiry, peersViewModel.myNickname)
                        Toast.makeText(context, "Inquiry sent! Check Chat tab.", Toast.LENGTH_LONG).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("💬 Chat")
                }
            }
        }
    }
}

@Composable
fun CreateRideDialog(
    myRole: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit
) {
    val context = LocalContext.current
    val calendar = Calendar.getInstance()
    calendar.add(Calendar.HOUR, 1)
    
    var selectedDate by remember { mutableLongStateOf(calendar.timeInMillis) }
    
    val datePickerDialog = DatePickerDialog(
        context,
        { _, year, month, dayOfMonth ->
            calendar.set(year, month, dayOfMonth)
            TimePickerDialog(
                context,
                { _, hourOfDay, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    calendar.set(Calendar.MINUTE, minute)
                    selectedDate = calendar.timeInMillis
                },
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                true
            ).show()
        },
        calendar.get(Calendar.YEAR),
        calendar.get(Calendar.MONTH),
        calendar.get(Calendar.DAY_OF_MONTH)
    )

    val roleText = if (myRole == "DRIVER") "driver offer" else "passenger request"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🛫 Schedule Departure") },
        text = {
            Column {
                Text("Your route from the Map will be saved as a $roleText.")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "When do you plan to depart?",
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(onClick = { datePickerDialog.show() }, modifier = Modifier.fillMaxWidth()) {
                    Text("🗓️  Pick Departure Date & Time")
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "🛫 Departure: ${formatDate(selectedDate)}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedDate) }) {
                Text("✅ Publish")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("EEE dd.MM HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
