package com.example.samizdat

import android.app.Application
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.freehaven.tor.control.RawEventListener
import net.freehaven.tor.control.TorControlConnection
import org.torproject.jni.TorService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs Tor in-process via Guardian Project's tor-android ([TorService]) and controls it
 * through jtorctl.
 *
 * Public API (socksPort, onionAddress, isBootstrapped, statusMessage, startTor(), stopTor(),
 * bootstrapManager) is unchanged from the previous kmp-tor implementation.
 *
 * NOTE: Tor runs inside this app process (JNI, tor_run_main). Tor only supports one instance
 * per process at a time, and running it a second time in the same process is only
 * "best effort" upstream (tor bug #23847). See [startTor] for how a restart is handled.
 *
 * BUILD NOTE: tor-android's classes are Java 24 bytecode (class 68). kapt compiles Java stubs of
 * every Kotlin class with javac from JDK 17, which cannot read them. So `TorService` must NEVER
 * appear in a field type, method parameter or return type in this module, only inside function
 * bodies (kapt does not stub bodies). That is why [torService] is typed as [Service].
 */
class SamizdatTorManager(private val context: Application) {

    private val _onionAddress = MutableStateFlow<String?>(null)
    val onionAddress: StateFlow<String?> = _onionAddress.asStateFlow()
    
    private val _isBootstrapped = MutableStateFlow(false)
    val isBootstrapped: StateFlow<Boolean> = _isBootstrapped.asStateFlow()
    
    private val _statusMessage = MutableStateFlow("Tor: Initializing...")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _socksPort = MutableStateFlow<Int?>(null)
    val socksPort: StateFlow<Int?> = _socksPort.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleMutex = Mutex()
    private val keyFile by lazy { File(context.filesDir, "onion_v3_private_key") }

    @Volatile private var torService: Service? = null // always a TorService, see BUILD NOTE
    @Volatile private var isBound = false
    @Volatile private var receiverRegistered = false
    private val readyHandled = AtomicBoolean(false)
    private val bootstrapListenerAdded = AtomicBoolean(false)
    
    val bootstrapManager = NostrBootstrapManager(context)

    private val bootstrapListener = RawEventListener { keyword, data ->
        // e.g. keyword="STATUS_CLIENT", data="NOTICE BOOTSTRAP PROGRESS=45 TAG=... SUMMARY=..."
        if (keyword == EVENT_STATUS_CLIENT && data != null && data.contains("BOOTSTRAP")) {
            BOOTSTRAP_PROGRESS.find(data)?.groupValues?.get(1)?.let { pct ->
                if (!_isBootstrapped.value) {
                    _statusMessage.value = "Tor: Bootstrapping $pct%"
                }
            }
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val pkg = intent.getStringExtra(TorService.EXTRA_SERVICE_PACKAGE_NAME)
            if (pkg != null && pkg != context.packageName) return

            when (intent.action) {
                TorService.ACTION_STATUS -> {
                    val status = intent.getStringExtra(TorService.EXTRA_STATUS)
                    Log.d(TAG, "TorService status: $status")
                    when (status) {
                        TorService.STATUS_STARTING -> {
                            if (!_isBootstrapped.value) _statusMessage.value = "Tor: Starting daemon..."
                        }
                        TorService.STATUS_ON -> scope.launch { onTorReady() }
                        TorService.STATUS_STOPPING, TorService.STATUS_OFF -> {
                            if (status == TorService.STATUS_OFF) lastStopElapsed = SystemClock.elapsedRealtime()
                            resetState()
                        }
                    }
                }
                TorService.ACTION_ERROR -> {
                    val msg = intent.getStringExtra(Intent.EXTRA_TEXT)
                    Log.e(TAG, "TorService error: $msg")
                    _statusMessage.value = "Tor: Error - $msg"
                }
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? TorService.LocalBinder)?.service ?: return
            torService = service
            Log.i(TAG, "Bound to TorService (Tor ${TorService.VERSION_NAME})")
            scope.launch { attachBootstrapListener(service) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Only called if the hosting process dies; TorService is in-process, so rare.
            Log.w(TAG, "TorService disconnected")
            torService = null
            resetState()
        }
    }

    fun startTor() {
        scope.launch {
            lifecycleMutex.withLock {
                if (isBound) return@launch

                try {
                    // A previous run in this process may still be shutting down. tor_run_main must
                    // not run concurrently, so give the old tor thread time to exit.
                    if (hasRunInProcess) {
                        val sinceStop = SystemClock.elapsedRealtime() - lastStopElapsed
                        if (sinceStop < RESTART_GRACE_MS) {
                            _statusMessage.value = "Tor: Waiting for previous instance to stop..."
                            delay(RESTART_GRACE_MS - sinceStop)
                        }
                        Log.w(TAG, "Restarting Tor in the same process (best effort, tor bug #23847)")
                    }

                    _statusMessage.value = "Tor: Creating environment..."
                    cleanupLegacyKmpTorDirs()
                    writeTorrc()

                    withContext(Dispatchers.Main) {
                        val filter = IntentFilter().apply {
                            addAction(TorService.ACTION_STATUS)
                            addAction(TorService.ACTION_ERROR)
                        }
                        LocalBroadcastManager.getInstance(context).registerReceiver(statusReceiver, filter)
                        receiverRegistered = true

                        _statusMessage.value = "Tor: Starting daemon..."
                        isBound = context.bindService(
                            Intent(context, TorService::class.java),
                            serviceConnection,
                            Context.BIND_AUTO_CREATE
                        )
                    }
                    if (!isBound) {
                        _statusMessage.value = "Tor: Init Error - could not bind TorService"
                        unregisterReceiver()
                    } else {
                        hasRunInProcess = true
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to initialize Tor", e)
                    _statusMessage.value = "Tor: Init Error - ${e.message}"
                }
            }
        }
    }

    /**
     * torrc overrides TorService's torrc-defaults, which would otherwise pick the fixed default
     * SOCKS/HTTP tunnel ports when they are free.
     * We never use a fixed SOCKS port (see DEVELOPMENT_NOTES §3); the real port is read back from
     * GETINFO net/listeners/socks once Tor is up.
     */
    private fun writeTorrc() {
        val torrc = TorService.getTorrc(context)
        torrc.parentFile?.mkdirs()
        torrc.writeText(
            """
            SOCKSPort auto
            HTTPTunnelPort 0
            """.trimIndent() + "\n"
        )
    }

    /** One-time removal of kmp-tor's old work/cache dirs. Never touches the onion key file. */
    private fun cleanupLegacyKmpTorDirs() {
        val torServiceCache = File(context.cacheDir, "TorService").canonicalPath
        listOf(File(context.filesDir, "torservice"), File(context.cacheDir, "torservice")).forEach { dir ->
            try {
                // Guard against case-insensitive filesystems where "torservice" == "TorService".
                if (dir.exists() && dir.canonicalPath != torServiceCache && dir.name == "torservice") {
                    if (dir.deleteRecursively()) Log.i(TAG, "Removed legacy kmp-tor dir: ${dir.path}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not remove legacy dir ${dir.path}: ${e.message}")
            }
        }
    }

    private suspend fun awaitControlConnection(service: Service): TorControlConnection? {
        val tor = service as TorService
        // TorService opens its control socket on a background thread shortly after onCreate.
        repeat(CONTROL_WAIT_STEPS) {
            tor.torControlConnection?.let { return it }
            if (!isBound) return null
            delay(CONTROL_WAIT_STEP_MS)
        }
        return tor.torControlConnection
    }

    private suspend fun attachBootstrapListener(service: Service) {
        val conn = awaitControlConnection(service) ?: run {
            Log.w(TAG, "Control connection not available; bootstrap % will not be shown")
            return
        }
        // TorService already subscribes to STATUS_CLIENT (bootstrap events are STATUS_CLIENT).
        // Do NOT call setEvents() without STATUS_CLIENT, or TorService breaks.
        if (bootstrapListenerAdded.compareAndSet(false, true)) {
            conn.addRawEventListener(bootstrapListener)
        }
    }

    private suspend fun onTorReady() {
        if (!readyHandled.compareAndSet(false, true)) return
        val service = torService ?: run { readyHandled.set(false); return }
        val conn = awaitControlConnection(service) ?: run {
            _statusMessage.value = "Tor: Error - no control connection"
            readyHandled.set(false)
            return
        }

        try {
            val listeners = conn.getInfo("net/listeners/socks") ?: ""
            val port = SOCKS_PORT.find(listeners)?.groupValues?.get(1)?.toIntOrNull()
            if (port == null) {
                Log.e(TAG, "Could not parse SOCKS listener from '$listeners'")
                _statusMessage.value = "Tor: Error - SOCKS port unavailable"
                readyHandled.set(false)
                return
            }
            _socksPort.value = port
            Log.i(TAG, "Detected SOCKS Port: $port")

            _isBootstrapped.value = true
            _statusMessage.value = "Tor: Ready! Creating service..."
            setupHiddenService(conn)
        } catch (e: Exception) {
            Log.e(TAG, "Tor ready handling failed", e)
            _statusMessage.value = "Tor: Error - ${e.message}"
        }
    }

    private fun loadPrivateKey(): ByteArray? {
        if (!keyFile.exists()) return null
        return try {
            val bytes = keyFile.readBytes()
            Log.i(TAG, "Loading existing Private Key (${bytes.size} bytes)")
            if (bytes.size == ED25519_EXPANDED_KEY_BYTES) bytes else {
                Log.e(TAG, "Unexpected key length ${bytes.size}, generating new one")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load key, generating new one", e)
            null
        }
    }

    private fun setupHiddenService(conn: TorControlConnection) {
        Log.i(TAG, "Setting up Hidden Service...")

        // Virtual port 80 -> local server on 12345 (PeerRepository/ConnectionManager).
        val ports = mapOf(80 to "127.0.0.1:12345")
        val existingKey = loadPrivateKey()

        val result: Map<String, String> = try {
            if (existingKey != null) {
                conn.addOnion("ED25519-V3:" + Base64.encodeToString(existingKey, Base64.NO_WRAP), ports)
            } else {
                conn.addOnion("NEW:ED25519-V3", ports)
            }
        } catch (e: Exception) {
            _statusMessage.value = "Tor: HS Error - ${e.message}"
            Log.e(TAG, "HS Creation Failed", e)
            return
        }

        val rawAddress = result["onionAddress"]
        if (rawAddress.isNullOrBlank()) {
            _statusMessage.value = "Tor: HS Error - no onion address returned"
            return
        }
        val address = OnionUtils.ensureOnionSuffix(rawAddress)
        Log.i(TAG, "Hidden Service created: $address")

        // Tor only returns PrivateKey for NEW keys. Persist it in the same raw 64-byte format.
        if (existingKey == null) {
            result["onionPrivKey"]?.let { saveNewPrivateKey(it) }
                ?: Log.e(TAG, "Tor did not return a private key for the new onion service")
        }

        _onionAddress.value = address
        _statusMessage.value = "Tor: Online at $address"

        // Publish our newly created Onion address to Nostr so others can find us
        try {
            bootstrapManager.publishOurOnion(address) { status ->
                _statusMessage.value = status
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nostr Bootstrap failed (non-fatal): ${e.message}", e)
            _statusMessage.value = "Tor: Online (Nostr bootstrap skipped)"
        }
    }

    private fun saveNewPrivateKey(torKey: String) {
        try {
            val b64 = torKey.removePrefix("ED25519-V3:")
            val bytes = Base64.decode(b64, Base64.DEFAULT)
            // Log only the length, never the key itself.
            Log.i(TAG, "New onion key from Tor: ${bytes.size} bytes")
            if (bytes.size != ED25519_EXPANDED_KEY_BYTES) {
                Log.e(TAG, "Unexpected onion key length ${bytes.size}; not saving")
                return
            }
            val tmp = File(keyFile.parentFile, keyFile.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(keyFile)) {
                keyFile.writeBytes(bytes)
                tmp.delete()
            }
            Log.i(TAG, "Saved Private Key (${bytes.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save private key", e)
        }
    }

    private fun resetState() {
        _isBootstrapped.value = false
        _socksPort.value = null
        readyHandled.set(false)
    }

    private fun unregisterReceiver() {
        if (receiverRegistered) {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
    }

    fun stopTor() {
        scope.launch {
            lifecycleMutex.withLock {
                if (!isBound) return@launch

                Log.i(TAG, "Stopping Tor...")
                _statusMessage.value = "Tor: Stopping..."

                withContext(Dispatchers.Main) {
                    try {
                        (torService as? TorService)?.torControlConnection?.removeRawEventListener(bootstrapListener)
                    } catch (_: Exception) { }
                    try {
                        context.unbindService(serviceConnection)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "unbindService: ${e.message}")
                    }
                    context.stopService(Intent(context, TorService::class.java))
                    unregisterReceiver()
                }
                isBound = false
                torService = null
                bootstrapListenerAdded.set(false)
                lastStopElapsed = SystemClock.elapsedRealtime()
                resetState()

                Log.i(TAG, "Tor stopped")
                _statusMessage.value = "Tor: Stopped"
            }
        }
    }

    companion object {
        private const val TAG = "Tor"
        private const val EVENT_STATUS_CLIENT = "STATUS_CLIENT"
        private const val ED25519_EXPANDED_KEY_BYTES = 64
        private const val CONTROL_WAIT_STEPS = 120
        private const val CONTROL_WAIT_STEP_MS = 250L
        private const val RESTART_GRACE_MS = 5_000L

        private val BOOTSTRAP_PROGRESS = Regex("""PROGRESS=(\d+)""")
        // net/listeners/socks -> "\"127.0.0.1:NNNN\"" (possibly several, space separated)
        private val SOCKS_PORT = Regex(""":(\d+)""")

        // Process-wide: TorService/tor_run_main state is static, managers are per-Activity.
        @Volatile private var hasRunInProcess = false
        @Volatile private var lastStopElapsed = 0L
    }
}
