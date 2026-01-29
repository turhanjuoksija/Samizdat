package com.example.samizdat

import android.app.Application
import android.content.Context
import android.util.Log
import io.matthewnelson.kmp.file.File
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.Action
import io.matthewnelson.kmp.tor.runtime.TorState
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3.PrivateKey.Companion.toED25519_V3PrivateKey
import io.matthewnelson.kmp.tor.runtime.core.key.AddressKey
import io.matthewnelson.kmp.tor.runtime.core.net.Port
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Ephemeral.Companion.toPortEphemeral
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SamizdatTorManager(private val context: Application) {

    private val _onionAddress = MutableStateFlow<String?>(null)
    val onionAddress: StateFlow<String?> = _onionAddress.asStateFlow()
    
    private val _isBootstrapped = MutableStateFlow(false)
    val isBootstrapped: StateFlow<Boolean> = _isBootstrapped.asStateFlow()
    
    private val _statusMessage = MutableStateFlow("Tor: Initializing...")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _socksPort = MutableStateFlow<Int?>(null)
    val socksPort: StateFlow<Int?> = _socksPort.asStateFlow()

    private var torRuntime: TorRuntime? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val keyFile by lazy { File(context.filesDir, "onion_v3_private_key") }

    fun startTor() {
        scope.launch {
            if (torRuntime != null) return@launch

            try {
                _statusMessage.value = "Tor: Creating environment..."
                
                val workDir = File(context.filesDir, "torservice")
                val cacheDir = File(context.cacheDir, "torservice")
                
                val environment = TorRuntime.Environment.Builder(
                    workDirectory = workDir,
                    cacheDirectory = cacheDir,
                    loader = { dir ->
                        ResourceLoaderTorExec.getOrCreate(dir)
                    }
                )
                environment.debug = true
                
                _statusMessage.value = "Tor: Building runtime..."
                
                torRuntime = TorRuntime.Builder(environment) {
                    config {
                        TorOption.SocksPort.configure {
                            port(9050.toPortEphemeral())
                        }
                    }

                    val logObserver = OnEvent<String> { message ->
                        Log.d("Tor", message)
                        // Parse SOCKS port from log: "Opened Socks listener on 127.0.0.1:XXXX"
                        if (message.contains("Opened Socks listener")) {
                            val match = Regex(":(\\d+)").find(message)
                            match?.groupValues?.get(1)?.toIntOrNull()?.let { port ->
                                _socksPort.value = port
                                Log.i("Tor", "Detected SOCKS Port: $port")
                            }
                        }
                    }

                    RuntimeEvent.entries().forEach { event ->
                        if (event is RuntimeEvent.LOG) {
                            observerStatic(event, logObserver)
                        }
                    }

                    observerStatic(RuntimeEvent.ERROR) { throwable ->
                        Log.e("Tor", "Runtime Error", throwable)
                        _statusMessage.value = "Tor: Error - ${throwable.message}"
                    }

                    observerStatic(RuntimeEvent.READY) { 
                        _statusMessage.value = "Tor: Ready! Creating service..."
                        _isBootstrapped.value = true
                        setupHiddenService()
                    }

                    observerStatic(RuntimeEvent.STATE) { state ->
                        val daemonState = state.daemon
                        if (daemonState is TorState.Daemon.On) {
                            _statusMessage.value = "Tor: Bootstrapping ${daemonState.bootstrap}%"
                        }
                        Log.d("Tor", "State: $state")
                    }
                    
                    observerStatic(RuntimeEvent.LIFECYCLE) { lifecycle ->
                        Log.d("Tor", "Lifecycle: $lifecycle")
                    }
                }
                
                _statusMessage.value = "Tor: Starting daemon..."
                torRuntime?.startDaemonAsync()
                
            } catch (e: Throwable) {
                Log.e("Tor", "Failed to initialize Tor", e)
                _statusMessage.value = "Tor: Init Error - ${e.message}"
            }
        }
    }

    private fun setupHiddenService() {
        val runtime = torRuntime ?: return
        Log.i("Tor", "Setting up Hidden Service...")

        val privateKey: ED25519_V3.PrivateKey? = if (keyFile.exists()) {
            try {
                val bytes = keyFile.readBytes()
                Log.i("Tor", "Loading existing Private Key (${bytes.size} bytes)")
                // Using extension function toED25519_V3PrivateKey()
                bytes.toED25519_V3PrivateKey()
            } catch (e: Exception) {
                Log.e("Tor", "Failed to load key, generating new one", e)
                null
            }
        } else {
            null
        }

        // Create a new Onion Service mapping virtual port 80 to local port 12345
        // If pk is null, it generates a new one.
        val pk: AddressKey.Private? = privateKey
        val onionAddCmd = if (pk != null) {
            TorCmd.Onion.Add.existing(pk) {
                port(virtual = Port.HTTP) {
                    target(port = 12345.toPort())
                }
            }
        } else {
            TorCmd.Onion.Add.new(ED25519_V3) {
                port(virtual = Port.HTTP) {
                    target(port = 12345.toPort())
                }
            }
        }

        runtime.enqueue(
            onionAddCmd,
            onFailure = { t ->
                _statusMessage.value = "Tor: HS Error - ${t.message}"
                Log.e("Tor", "HS Creation Failed", t)
            },
            onSuccess = { entry ->
                val address = OnionUtils.ensureOnionSuffix(entry.publicKey.address().value)
                
                Log.i("Tor", "Hidden Service created: $address")
                
                // Save the key if it's new or just to ensure it's there
                entry.privateKey?.let { key ->
                    try {
                        // Using encodedOrNull() as per search results
                        val bytes = key.encodedOrNull()
                        if (bytes != null) {
                            if (!keyFile.exists() || !keyFile.readBytes().contentEquals(bytes)) {
                                keyFile.writeBytes(bytes)
                                Log.i("Tor", "Saved Private Key (${bytes.size} bytes)")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("Tor", "Failed to save private key", e)
                    }
                    Unit
                }

                _onionAddress.value = address
                _statusMessage.value = "Tor: Online at $address"
            }
        )
    }

    fun stopTor() {
        scope.launch {
            val runtime = torRuntime ?: return@launch
            
            Log.i("Tor", "Stopping Tor...")
            _statusMessage.value = "Tor: Stopping..."
            
            runtime.enqueue(
                Action.StopDaemon,
                onSuccess = {
                    Log.i("Tor", "Tor stopped")
                    _isBootstrapped.value = false
                    _statusMessage.value = "Tor: Stopped"
                },
                onFailure = { error ->
                    Log.e("Tor", "Failed to stop Tor", error)
                }
            )
        }
    }
}
