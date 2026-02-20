package com.example.samizdat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class FutureRidesViewModel(
    private val repository: PeerRepository,
    private val peersViewModel: PeersViewModel // We need this to access dhtManager and logic
) : ViewModel() {

    val activeIntents: StateFlow<List<RideIntent>> = repository.activeRideIntents
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Republish Loop
    init {
        viewModelScope.launch {
            while (true) {
                // Expire old intents first
                repository.expireOldIntents()
                
                // Then publish active ones
                val intents = activeIntents.value
                if (intents.isNotEmpty() && peersViewModel.dhtManager.kademliaNode != null) {
                    val myNick = peersViewModel.myNickname
                    intents.forEach { intent ->
                        peersViewModel.dhtManager.publishIntent(intent, myNick)
                    }
                    // Log
                    // Log.d("FutureRides", "Republished ${intents.size} intents")
                }
                
                delay(15 * 60 * 1000) // Every 15 minutes
            }
        }
    }

    fun createIntent(
        type: String,
        originLat: Double,
        originLon: Double,
        destLat: Double,
        destLon: Double,
        departureTime: Long,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            val count = repository.getActiveIntentCount()
            if (count >= 5) {
                onError("You have reached the limit of 5 active requests. Please delete old ones.")
                return@launch
            }

            if (departureTime < System.currentTimeMillis()) {
                onError("Departure time must be in the future.")
                return@launch
            }

            val intent = RideIntent(
                type = type,
                originLat = originLat,
                originLon = originLon,
                destLat = destLat,
                destLon = destLon,
                departureTime = departureTime
            )

            repository.saveRideIntent(intent)
            
            // Immediate publish
            peersViewModel.dhtManager.publishIntent(intent, peersViewModel.myNickname)
            
            onSuccess()
        }
    }

    fun deleteIntent(intent: RideIntent) {
        viewModelScope.launch {
            repository.deleteRideIntent(intent)
        }
    }
}

class FutureRidesViewModelFactory(
    private val repository: PeerRepository,
    private val peersViewModel: PeersViewModel
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FutureRidesViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FutureRidesViewModel(repository, peersViewModel) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
