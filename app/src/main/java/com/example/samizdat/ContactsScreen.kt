package com.example.samizdat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.location.Location

@Composable
fun ContactsScreen(
    viewModel: PeersViewModel,
    savedPeers: List<Peer>,
    onPeerClick: (Peer) -> Unit,
    onScanQrClick: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showAddDialog by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // Observe live peer state changes
    val livePeersMap = viewModel.livePeerTimestamps.toMap()
    val nowMs = System.currentTimeMillis()
    
    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                text = "My Contacts",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                textAlign = TextAlign.Start
            )
        }

        if (savedPeers.isEmpty()) {
            item { Text("No saved contacts yet.", style = MaterialTheme.typography.bodyMedium, color = Color.Gray) }
        } else {
            items(savedPeers) { peer ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onPeerClick(peer) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val isLive = peer.onion?.let { onion ->
                                    val ts = livePeersMap[OnionUtils.ensureOnionSuffix(onion)]
                                    ts != null && (nowMs - ts) <= PeersViewModel.LIVE_PEER_WINDOW_MS
                                } ?: false
                                Text(
                                    text = if (isLive) "🟢 ${peer.nickname}" else peer.nickname,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "★".repeat(minOf(peer.reputationScore, 5)).ifEmpty { "☆" },
                                    color = Color(0xFFFF9800),
                                    fontSize = 14.sp
                                )
                                if (peer.reputationScore > 5) {
                                    Text("+${peer.reputationScore - 5}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (peer.onion != null) {
                                Text("Onion: ${peer.onion.take(16)}...", color = Color(0xFF6A0DAD), style = MaterialTheme.typography.bodySmall)
                            }
                            
                            // Distance Calculation
                            if (peer.latitude != null && peer.longitude != null && viewModel.myLatitude != null && viewModel.myLongitude != null) {
                                val results = FloatArray(1)
                                Location.distanceBetween(viewModel.myLatitude!!, viewModel.myLongitude!!, peer.latitude, peer.longitude, results)
                                val dist = results[0]
                                if (dist < 1000) {
                                     Text("Dist: ${dist.toInt()} m", style = MaterialTheme.typography.labelSmall, color = Color.Magenta)
                                } else {
                                     Text("Dist: ${"%.1f".format(dist / 1000)} km", style = MaterialTheme.typography.labelSmall, color = Color.Magenta)
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { 
                                    viewModel.vouchForPeer(peer)
                                    android.widget.Toast.makeText(context, "Vouched for ${peer.nickname}", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Vouch", fontSize = 10.sp)
                            }

                            IconButton(onClick = { viewModel.deletePeer(peer) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                            }
                        }
                    }
                }
            }
        }

    
        // FAB to Add Contact
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Text("+", fontSize = 24.sp, color = Color.White)
        }
        
        if (showAddDialog) {
            var manualOnion by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
            var manualNick by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

            fun applyPeerInput(raw: String) {
                val trimmed = raw.trim()
                if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                    try {
                        val json = org.json.JSONObject(trimmed)
                        val o = json.optString("o", "").trim()
                        val n = json.optString("n", "").trim()
                        if (o.isNotEmpty()) manualOnion = o
                        if (n.isNotEmpty() && manualNick.isBlank()) manualNick = n.take(50)
                        return
                    } catch (_: Exception) { }
                }
                var cleaned = trimmed
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .substringBefore("/")
                    .replace(Regex("\\s+"), "")
                if (cleaned.length <= 120) {
                    manualOnion = cleaned
                }
            }
            
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showAddDialog = false },
                title = { Text("Add Peer Manually") },
                text = {
                    Column {
                        Text("Paste a .onion address (62 chars) or scan a peer's QR code.", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                                    if (!clipText.isNullOrBlank()) {
                                        applyPeerInput(clipText)
                                    } else {
                                        android.widget.Toast.makeText(context, "Clipboard is empty", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("📋 Paste")
                            }
                            OutlinedButton(
                                onClick = {
                                    showAddDialog = false
                                    onScanQrClick()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("📷 Scan QR")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualOnion,
                            onValueChange = { input -> applyPeerInput(input) },
                            label = { Text("Onion Address") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = manualNick,
                            onValueChange = { if (it.length <= 50) manualNick = it },
                            label = { Text("Local Nickname") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val cleanOnion = manualOnion.trim()
                            val cleanNick = manualNick.trim().ifEmpty { "Peer_${cleanOnion.take(6)}" }
                            if (cleanOnion.isNotEmpty()) {
                                viewModel.addManualPeer(cleanOnion, cleanNick)
                                android.widget.Toast.makeText(context, "Ping sent to $cleanNick!", android.widget.Toast.LENGTH_SHORT).show()
                                showAddDialog = false
                            }
                        }
                    ) {
                        Text("Add & Ping")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
