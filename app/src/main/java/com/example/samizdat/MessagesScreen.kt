package com.example.samizdat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Message
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MessagesScreen(
    viewModel: PeersViewModel,
    myNickname: String
) {
    val conversations by viewModel.recentConversations.collectAsState(initial = emptyList())
    val storedPeers by viewModel.storedPeers.collectAsState(initial = emptyList())
    
    // State for handling dialogs
    var showChatDialog by remember { mutableStateOf<Peer?>(null) }
    var showNewChatDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewChatDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "New Chat")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                text = "Messages 💬",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (conversations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No conversations yet.", color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Start a new chat with + button!", style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(conversations) { lastMsg ->
                        val peerPk = lastMsg.peerPublicKey
                        // Find peer info if available
                        val peer = storedPeers.find { it.publicKey == peerPk || it.onion == peerPk }
                        val displayName = peer?.nickname ?: peerPk.take(8) + "..."
                        
                        ConversationItem(
                            displayName = displayName,
                            message = lastMsg,
                            isTrusted = peer?.isTrusted ?: false,
                            onClick = {
                                // Create a transient peer object if we don't have one stored, just to open the chat
                                val chatPeer = peer ?: Peer(
                                    publicKey = peerPk, 
                                    nickname = displayName, 
                                    lastKnownIp = peerPk, // Fallback
                                    onion = if (peerPk.endsWith(".onion")) peerPk else null,
                                    isTrusted = false,
                                    lastSeenTimestamp = System.currentTimeMillis()
                                )
                                showChatDialog = chatPeer
                            }
                        )
                    }
                }
            }
        }
    }

    // Chat Dialog (Reuse the one from MainScreen logic conceptually, but we need to instantiate it here or pass callback)
    // Ideally we should lift the state, but for now we reuse the composable logic locally.
    // Note: We need access to MessageDialog composable. It is currently in MainActivity.kt but accessible if public.
    // Or we can just include it here if we move it to a shared file. 
    // Since it's in MainActivity.kt, we can't easily call it if it's not in a separate file.
    // Let's implement a wrapper that uses the MainActivity's MessageDialog if possible, OR move MessageDialog to a shared file.
    // For expediency, I will implement a local 'ChatDialogWrapper' that mimics the one in MainActivity.
    
    if (showChatDialog != null) {
        MessageDialog(
            peer = showChatDialog!!,
            viewModel = viewModel,
            myNickname = myNickname,
            onDismiss = { showChatDialog = null }
        )
    }

    if (showNewChatDialog) {
        NewChatDialog(
            peers = storedPeers,
            onDismiss = { showNewChatDialog = false },
            onPeerSelected = { peer ->
                showNewChatDialog = false
                showChatDialog = peer
            }
        )
    }
}

@Composable
fun ConversationItem(
    displayName: String,
    message: ChatMessage,
    isTrusted: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar / Icon
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = if (isTrusted) MaterialTheme.colorScheme.primaryContainer else Color.LightGray,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = displayName.take(1).uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = if (isTrusted) MaterialTheme.colorScheme.onPrimaryContainer else Color.Black
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = displayName, 
                        style = MaterialTheme.typography.titleMedium, 
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = convertTimestamp(message.timestamp), 
                        style = MaterialTheme.typography.labelSmall, 
                        color = Color.Gray
                    )
                }
                Text(
                    text = if (message.isIncoming) message.content else "You: ${message.content}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.DarkGray
                )
            }
        }
    }
}

@Composable
fun NewChatDialog(
    peers: List<Peer>,
    onDismiss: () -> Unit,
    onPeerSelected: (Peer) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a New Chat") },
        text = {
            if (peers.isEmpty()) {
                Text("No contacts found. Scan a QR code first!")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(peers) { peer ->
                         Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPeerSelected(peer) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(peer.nickname, style = MaterialTheme.typography.bodyLarge)
                            if (peer.isTrusted) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("✅", fontSize = 12.sp)
                            }
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// Utility to reuse the timestamp formatter
private fun convertTimestamp(ts: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(ts))
}

