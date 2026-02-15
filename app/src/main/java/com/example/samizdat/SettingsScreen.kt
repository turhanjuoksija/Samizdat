package com.example.samizdat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: PeersViewModel,
    myNickname: String,
    onNicknameChange: (String) -> Unit,
    onionAddress: String?,
    torStatus: String,
    qrBitmap: Bitmap?,
    myHash: String?
) {
    val context = LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Identity Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Identity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (onionAddress != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Your ID: ${onionAddress.take(16)}...", color = Color(0xFF6A0DAD))
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Onion", onionAddress))
                                Toast.makeText(context, "Copied Address!", Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.Share, contentDescription = "Copy")
                            }
                        }
                    } else {
                        Text("Tor Status: $torStatus", color = Color.Gray)
                    }

                    OutlinedTextField(
                        value = myNickname,
                        onValueChange = onNicknameChange,
                        label = { Text("Nickname") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (qrBitmap != null) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Contact QR",
                            modifier = Modifier.size(150.dp)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Location Mode: ", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (viewModel.isSetLocationMode) "📍 OVERRIDE (Set on Map)" else "🛰️ GPS Active",
                            color = if (viewModel.isSetLocationMode) Color.Red else Color.DarkGray,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = viewModel.isSetLocationMode,
                            onCheckedChange = { viewModel.isSetLocationMode = it }
                        )
                    }
                    if (viewModel.isSetLocationMode) {
                        Text("Go to Map tab and long-press to set your virtual position.", fontSize = 10.sp, color = Color.Gray)
                    }
                }
            }
        }

        // Reputation & WoT Section
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE6E6FA))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Reputation & WoT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val allVouches by viewModel.allVouches.collectAsState(initial = emptyList())
                    val myOnion = onionAddress ?: ""
                    val currentMyHash = myHash ?: ""
                    val vouchesIReceived = allVouches.filter { it.targetPk == currentMyHash || (myOnion.isNotEmpty() && it.targetPk == myOnion) }.size
                    val vouchesIGave = allVouches.filter { it.voucherPk == myOnion }.size

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Received", style = MaterialTheme.typography.labelSmall)
                            Text("★ $vouchesIReceived", style = MaterialTheme.typography.headlineMedium, color = Color(0xFFFFD700))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Given", style = MaterialTheme.typography.labelSmall)
                            Text("🤝 $vouchesIGave", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF6A5ACD))
                        }
                    }
                    Text("Reputation is calculated locally based on trust vouches collected from the network.", fontSize = 10.sp, color = Color.Gray, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        // Debug Logs
        item {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Debug Logs", color = Color.Green, style = MaterialTheme.typography.labelSmall)
                        IconButton(onClick = {
                            val logs = viewModel.debugLogs.take(200).joinToString("\n")
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, logs)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "Samizdat Debug Logs")
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share Logs"))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share Logs", tint = Color.Green)
                        }
                    }
                    Box(modifier = Modifier.height(150.dp)) {
                        LazyColumn {
                            items(viewModel.debugLogs) { log ->
                                Text(log, color = Color.Green, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
