package com.example.samizdat

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.location.Location
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import android.net.nsd.NsdServiceInfo
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import com.example.samizdat.ConnectionManager
import com.example.samizdat.PeerRepository
import com.example.samizdat.PeersViewModel
import com.example.samizdat.PeersViewModelFactory

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: PeersViewModel
    private var multicastLock: WifiManager.MulticastLock? = null
    
    // Identity State
    private var myPublicKeyHash by mutableStateOf<String?>(null)
    private var myFullPublicKey by mutableStateOf<String?>(null)
    private var myNickname by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // --- Dependency Injection (Manual) ---
        val context = applicationContext
        val db = AppDatabase.getDatabase(context)
        val nsdHelper = NsdHelper(context)
        
        // Tor Manager (Requires Application Context) - Held by ViewModel for survival
        val torManager = SamizdatTorManager(application)
        
        val connectionManager = ConnectionManager()
        val repository = PeerRepository(db.peerDao(), db.messageDao(), db.vouchDao(), nsdHelper, connectionManager)
        val updateManager = UpdateManager(application, torManager)
        
        val factory = PeersViewModelFactory(repository, nsdHelper, torManager, updateManager)
        viewModel = ViewModelProvider(this, factory)[PeersViewModel::class.java]

        // Load saved nickname
        val prefs = getSharedPreferences("samizdat_prefs", Context.MODE_PRIVATE)
        val savedNick = prefs.getString("nickname", null)
        myNickname = savedNick ?: "Rebel_${(1000..9999).random()}"

        // Generate ID immediately
        lifecycleScope.launch {
            try {
                val (hash, fullKey) = generateAndHashKey()
                myPublicKeyHash = hash
                myFullPublicKey = fullKey
                // Initial registration
                viewModel.registerService(myNickname, hash)
            } catch (e: Exception) {
                Log.e("MainActivity", "Error generating identity", e)
            }
        }

        // Re-register when Onion Address becomes available
        lifecycleScope.launch {
            viewModel.torOnionAddress.collect { onion ->
                if (onion != null && myPublicKeyHash != null) {
                    Log.i("MainActivity", "Tor Online! Re-registering with Onion: $onion")
                    viewModel.updateMyStatus(myNickname, myPublicKeyHash!!)
                }
            }
        }

        try {
            setContent {
                MaterialTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        val torStatus by viewModel.torStatus.collectAsState()
                        val onionAddr by viewModel.torOnionAddress.collectAsState()

                        MainScreen(
                            viewModel = viewModel,
                            myIp = getLocalIpAddress() ?: "Unavailable",
                            myHash = myPublicKeyHash,
                            myFullPubKey = myFullPublicKey,
                            myNickname = myNickname,
                            torStatus = torStatus,
                            onionAddress = onionAddr,
                            onNicknameChange = { newNick ->
                                myNickname = newNick
                                prefs.edit().putString("nickname", newNick).apply()
                                if (myPublicKeyHash != null) {
                                    viewModel.registerService(newNick, myPublicKeyHash!!)
                                }
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error setting content", e)
        }
        
        // Listen for incoming messages to show Toasts (Side Effect)
        lifecycleScope.launch {
            try {
                viewModel.incomingMessages.collect { (senderIp, msg) ->
                    Toast.makeText(this@MainActivity, "Msg from $senderIp: $msg", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                 Log.e("MainActivity", "Error collecting messages", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        acquireMulticastLock()
        // Register & Discover
        val hash = myPublicKeyHash
        if (hash != null) {
            viewModel.registerService(myNickname, hash)
        }
        viewModel.startDiscovery()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopDiscovery()
        releaseMulticastLock()
    }

    private fun acquireMulticastLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("SamizdatMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.release()
    }
}

enum class SamizdatTab(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    CONTACTS("Contacts", Icons.Default.People),
    MAP("Map", Icons.Default.Map),
    OFFERS("Offers", Icons.Default.LocalOffer),
    MESSAGES("Messages", Icons.Default.Forum),
    SETTINGS("Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: PeersViewModel,
    myIp: String,
    myHash: String?,
    myFullPubKey: String?,
    myNickname: String,
    torStatus: String,
    onionAddress: String?,
    onNicknameChange: (String) -> Unit
) {
    LaunchedEffect(myFullPubKey) {
        viewModel.myFullPubKey = myFullPubKey
    }
    LaunchedEffect(myNickname) {
        viewModel.myNickname = myNickname
    }
    val context = LocalContext.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    
    // Dialog State
    var showMessageDialog by remember { mutableStateOf<Peer?>(null) }
    
    // State Observation
    val savedPeers by viewModel.storedPeers.collectAsState(initial = emptyList())
    val discoveredPeers = viewModel.nsdHelper.discoveredPeers

    // QR Code
    val qrContent = remember(myNickname, onionAddress, myIp) {
        val onion = onionAddress ?: myIp
        """{"n":"$myNickname","o":"$onion"}"""
    }
    val qrBitmap = remember(qrContent) { generateQrCode(qrContent) }

    // Navigation State
    var selectedTab by rememberSaveable { mutableStateOf(SamizdatTab.MAP) }

    // Scanner logic
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val content = result.contents
            try {
                val json = org.json.JSONObject(content)
                val rawOnion = json.getString("o")
                val nick = json.getString("n")
                val onion = OnionUtils.ensureOnionSuffix(rawOnion)
                viewModel.savePeer(onion, nick, onion, onion)
                Toast.makeText(context, "Scanned: $nick", Toast.LENGTH_SHORT).show()
                selectedTab = SamizdatTab.CONTACTS
            } catch (e: Exception) {
                viewModel.savePeer(content, "Manual", content)
                Toast.makeText(context, "Scanned: $content", Toast.LENGTH_SHORT).show()
                selectedTab = SamizdatTab.CONTACTS
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scanLauncher.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scan Samizdat QR")
                setBeepEnabled(false)
            })
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val locationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
                locationClient.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY, null)
                    .addOnSuccessListener { loc -> loc?.let { viewModel.updateLocation(it.latitude, it.longitude) } }
            }
        }
    }

    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    if (showMessageDialog != null) {
        MessageDialog(
            peer = showMessageDialog!!,
            viewModel = viewModel,
            myNickname = myNickname,
            onDismiss = { showMessageDialog = null }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 8.dp
            ) {
                SamizdatTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        label = { Text(tab.label, fontSize = 10.sp) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectedTab == SamizdatTab.SETTINGS) {
                FloatingActionButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan")
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            when (selectedTab) {
                SamizdatTab.CONTACTS -> {
                    // Filter out own contact from list
                    val myOnion = onionAddress
                    val filteredContacts = savedPeers.filter { peer ->
                        peer.onion != myOnion && peer.publicKey != myOnion
                    }
                    
                    ContactsScreen(
                        viewModel = viewModel,
                        savedPeers = filteredContacts,
                        discoveredPeers = discoveredPeers,
                        onPeerClick = { showMessageDialog = it }
                    )
                }
                SamizdatTab.MAP -> {
                    val myLoc = if (viewModel.myLatitude != null && viewModel.myLongitude != null) {
                         GeoPoint(viewModel.myLatitude!!, viewModel.myLongitude!!)
                    } else null

                    val myDest = if (viewModel.myDestLat != null && viewModel.myDestLon != null) {
                         GeoPoint(viewModel.myDestLat!!, viewModel.myDestLon!!)
                    } else null

                    // Waypoints list is accessed directly by MapComposable via ViewModel
                    
                    // Filter out own onion address to prevent self-display as peer
                    val myOnion = onionAddress
                    val filteredPeers = savedPeers.filter { peer ->
                        peer.onion != myOnion && peer.publicKey != myOnion
                    }

                    MapComposable(
                        modifier = Modifier.fillMaxSize(),
                        myLocation = myLoc,
                        myDestination = myDest,
                        // myWaypoint arg removed
                        peers = filteredPeers,
                        isWaypointMode = viewModel.isWaypointMode,
                        onMapLongClick = { point ->
                            if (viewModel.isSetLocationMode) {
                                viewModel.updateLocation(point.latitude, point.longitude)
                                viewModel.isSetLocationMode = false
                                Toast.makeText(context, "Location Set!", Toast.LENGTH_SHORT).show()
                            } else {
                                // Unified entry: updateWaypoint handles Role logic (Driver append, Passenger set dest)
                                viewModel.updateWaypoint(point.latitude, point.longitude) {
                                    Toast.makeText(context, "Max 6 stops reached!", Toast.LENGTH_SHORT).show()
                                }
                                viewModel.calculateRoute()
                            }
                        },
                        onAddWaypoint = { point ->
                           viewModel.updateWaypoint(point.latitude, point.longitude) {
                               Toast.makeText(context, "Max 6 stops reached!", Toast.LENGTH_SHORT).show()
                           }
                           viewModel.isWaypointMode = false
                           viewModel.calculateRoute()
                        },
                        onToggleWaypointMode = { viewModel.isWaypointMode = !viewModel.isWaypointMode },
                        onClearAll = { viewModel.clearAll() },
                        onClearWaypoint = { viewModel.clearWaypoint() },
                        roadRoute = if (viewModel.myRole == "DRIVER") viewModel.roadRoute else null,
                        highlightedGrids = if (viewModel.myRole == "DRIVER") {
                            (viewModel.routeGrids + listOfNotNull(viewModel.myGridId)).distinct()
                        } else {
                            listOfNotNull(viewModel.myGridId) // Passenger: Highlight only current location grid (scanning grids handled internally)
                        },
                        currentGridId = viewModel.myGridId,
                        myRole = viewModel.myRole,
                        mySeats = viewModel.mySeats,
                        isBroadcasting = viewModel.isBroadcasting,
                        offers = viewModel.gridOffers,
                        onToggleBroadcasting = { 
                            viewModel.isBroadcasting = it
                            if (it) viewModel.broadcastStatus(myNickname)
                        },
                        viewModel = viewModel
                    )
                }
                SamizdatTab.OFFERS -> OffersScreen(viewModel = viewModel)
                SamizdatTab.MESSAGES -> MessagesScreen(viewModel = viewModel, myNickname = myNickname)
                SamizdatTab.SETTINGS -> SettingsScreen(
                    viewModel = viewModel,
                    myNickname = myNickname,
                    onNicknameChange = onNicknameChange,
                    onionAddress = onionAddress,
                    torStatus = torStatus,
                    qrBitmap = qrBitmap,
                    myHash = myHash
                )
            }
        }
    }
}

@Composable
fun MessageDialog(peer: Peer, viewModel: PeersViewModel, myNickname: String, onDismiss: () -> Unit) {
    var messageText by remember { mutableStateOf("") }
    val messages by viewModel.getMessagesForPeer(peer.publicKey).collectAsState(initial = emptyList())
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chat with ${peer.nickname}") },
        text = {
            Column {
                // Message History
                Box(modifier = Modifier.height(200.dp).fillMaxWidth()) {
                    LazyColumn(reverseLayout = true) {
                        items(messages.reversed()) { msg ->
                            val color = if (msg.isIncoming) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer
                            val align = if (msg.isIncoming) Alignment.Start else Alignment.End
                            
                            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = align) {
                                Surface(shape = MaterialTheme.shapes.medium, color = color) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = msg.content, modifier = Modifier.padding(8.dp))
                                        if (!msg.isIncoming) {
                                            val statusIcon = when (msg.status) {
                                                "PENDING" -> "⌛"
                                                "DELIVERED" -> "✔️✔️"
                                                "FAILED" -> "❌"
                                                else -> "✅" // SENT
                                            }
                                            Text(
                                                text = statusIcon, 
                                                style = MaterialTheme.typography.labelSmall, 
                                                color = if (msg.status == "FAILED") Color.Red else Color.Unspecified,
                                                modifier = Modifier.padding(end = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { 
                if (messageText.isNotEmpty()) {
                    viewModel.sendMessage(peer, messageText, myNickname) 
                    messageText = ""
                }
            }) {
                Text("Send")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

// --- Helpers --- (Same as before, omitted for brevity if unchanged, but included for complete file rewrite)

fun getLocalIpAddress(): String? {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val intf = interfaces.nextElement()
            val addrs = intf.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (!addr.isLoopbackAddress && addr is Inet4Address) {
                    return addr.hostAddress
                }
            }
        }
    } catch (ex: Exception) {
        Log.e("IP", "Error getting IP", ex)
    }
    return null
}

fun generateQrCode(text: String): Bitmap? {
    return try {
        val writer = MultiFormatWriter()
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, 400, 400)
        val w = matrix.width
        val h = matrix.height
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        for (x in 0 until w) {
            for (y in 0 until h) {
                bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        Log.e("QR", "Error generating QR", e)
        null
    }
}

suspend fun generateAndHashKey(): Pair<String, String> = withContext(Dispatchers.IO) {
    val alias = "samizdat_ed25519_key"
    val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
    keyStore.load(null)

    var publicKeyBytes: ByteArray
    
    if (keyStore.containsAlias(alias)) {
        try {
            // Key exists, load it
            val entry = keyStore.getEntry(alias, null) as? java.security.KeyStore.PrivateKeyEntry
            if (entry != null) {
                publicKeyBytes = entry.certificate.publicKey.encoded
            } else {
                Log.e("KeyMgmt", "Key entry is null or wrong type. Regenerating.")
                keyStore.deleteEntry(alias)
                publicKeyBytes = generateNewKey(alias)
            }
        } catch (e: Exception) {
            Log.e("KeyMgmt", "Error loading key: ${e.message}. Regenerating.", e)
            try {
                keyStore.deleteEntry(alias)
            } catch (ex: Exception) {
                Log.e("KeyMgmt", "Failed to delete corrupt key", ex)
            }
            publicKeyBytes = generateNewKey(alias)
        }
    } else {
        // Key doesn't exist, create new
        publicKeyBytes = generateNewKey(alias)
    }

    val hash = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes).joinToString("") { "%02x".format(it) }
    val fullKey = android.util.Base64.encodeToString(publicKeyBytes, android.util.Base64.NO_WRAP)
    
    Pair(hash, fullKey)
}

private fun generateNewKey(alias: String): ByteArray {
    val kpg: KeyPairGenerator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
    val parameterSpec = KeyGenParameterSpec.Builder(
        alias,
        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
    )
        .setAlgorithmParameterSpec(ECGenParameterSpec("ed25519"))
        .setDigests(KeyProperties.DIGEST_NONE)
        .build()

    kpg.initialize(parameterSpec)
    val keyPair = kpg.generateKeyPair()
    return keyPair.public.encoded
}
