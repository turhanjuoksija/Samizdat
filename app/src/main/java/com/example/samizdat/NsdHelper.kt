package com.example.samizdat

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import androidx.compose.runtime.mutableStateListOf

class NsdHelper(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    val discoveredPeers = mutableStateListOf<NsdServiceInfo>()
    var registeredServiceName: String? = null

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {
            Log.d("NSD", "Service discovery started")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            Log.d("NSD", "Service found: $service")
            if (service.serviceType == SERVICE_TYPE) {
                if (service.serviceName == registeredServiceName) {
                    Log.d("NSD", "Same machine: $registeredServiceName")
                } else if (service.serviceName.contains("Samizdat")) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e("NSD", "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            Log.d("NSD", "Resolve Succeeded. $serviceInfo")
                            // Check for duplicates based on Host/IP rather than Service instance to allow updates
                            val existingIndex = discoveredPeers.indexOfFirst { 
                                it.host?.hostAddress == serviceInfo.host?.hostAddress 
                            }
                            
                            if (existingIndex != -1) {
                                discoveredPeers[existingIndex] = serviceInfo
                            } else {
                                discoveredPeers.add(serviceInfo)
                            }
                        }
                    })
                }
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            Log.e("NSD", "service lost: $service")
            discoveredPeers.removeAll { it.serviceName == service.serviceName }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i("NSD", "Discovery stopped: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("NSD", "Discovery failed: Error code:$errorCode")
            nsdManager.stopServiceDiscovery(this)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("NSD", "Discovery failed: Error code:$errorCode")
            nsdManager.stopServiceDiscovery(this)
        }
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
            registeredServiceName = NsdServiceInfo.serviceName
            Log.d("NSD", "Service registered: $registeredServiceName")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("NSD", "Registration failed: $errorCode")
        }

        override fun onServiceUnregistered(arg0: NsdServiceInfo) {
            Log.d("NSD", "Service unregistered")
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("NSD", "Unregistration failed: $errorCode")
        }
    }

    fun registerService(
        port: Int, 
        nickname: String, 
        pubKeyHash: String,
        role: String = "NONE",
        seats: Int = 0,
        info: String = "",
        onion: String? = null
    ) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "Samizdat_${System.currentTimeMillis()}" 
            serviceType = SERVICE_TYPE
            setPort(port)
            // Add attributes for identification
            setAttribute("nick", nickname)
            setAttribute("hash", pubKeyHash)
            setAttribute("role", role)
            setAttribute("seats", seats.toString())
            setAttribute("info", info)
            onion?.let { setAttribute("onion", it) }
        }
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
             Log.e("NSD", "Register exception", e)
        }
    }

    fun discoverServices() {
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
             Log.e("NSD", "Discover exception", e)
        }
    }
    
    fun tearDown() {
        try {
            // Only unregister/stop if they were actually started/registered to avoid errors, 
            // but the API usually handles this safely or throws which we catch.
            if (registeredServiceName != null) {
               nsdManager.unregisterService(registrationListener)
            }
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            Log.e("NSD", "Teardown error", e)
        }
    }

    companion object {
        const val SERVICE_TYPE = "_samizdat._tcp."
    }
}
