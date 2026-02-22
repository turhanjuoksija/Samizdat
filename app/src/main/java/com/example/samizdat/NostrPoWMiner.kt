package com.example.samizdat

import android.util.Log
import fr.acinq.secp256k1.Hex

/**
 * Implements NIP-13 Proof of Work for Nostr Events.
 */
object NostrPoWMiner {
    private const val TAG = "NostrPoWMiner"

    /**
     * Mines a Nostr event by iterating a `nonce` tag until the resulting SHA-256 hash (event.id)
     * has at least `targetDifficulty` leading zero *bits*.
     * 
     * E.g., a difficulty of 16 means the first 4 hex characters of the ID must be '0'.
     *
     * @param event The NIP-01 event to mine (must not yet be signed).
     * @param targetDifficulty The required number of leading zero bits.
     * @param privateKeyHex The user's temporary private key to sign the event after mining.
     * @param onProgress Callback to send UI updates.
     * @return The correctly mined, hashed, and signed event.
     */
    fun mineEvent(
        event: NostrEvent, 
        targetDifficulty: Int, 
        privateKeyHex: String,
        onProgress: (String) -> Unit = {}
    ): NostrEvent {
        require(targetDifficulty > 0) { "Target difficulty must be greater than 0" }
        
        onProgress("Mining PoW (Target: $targetDifficulty bits)...")
        Log.i(TAG, "Mining event with target difficulty: $targetDifficulty bits.")

        // Setup base tags and the nonce tag we will modify
        val baseTags = event.tags.filter { it.isNotEmpty() && it[0] != "nonce" }.toMutableList()
        var currentNonce = 0L

        while (true) {
            // NIP-13 requires the nonce tag to include the target difficulty:
            // ["nonce", "random_string", "target_difficulty"]
            val nonceTag = listOf("nonce", currentNonce.toString(), targetDifficulty.toString())
            
            // Add the nonce tag at the end
            val newTags = ArrayList(baseTags)
            newTags.add(nonceTag)
            event.tags = newTags

            // Calculate the SHA-256 hash
            val hashHex = NostrCryptoUtils.calculateId(event)

            // Check if the hash meets the difficulty constraint
            if (countLeadingZeroBits(hashHex) >= targetDifficulty) {
                // Success! The hash is valid. Set the ID and sign the event.
                onProgress("Solved PoW! Nonce: $currentNonce")
                Log.i(TAG, "Successfully mined event! Nonce: $currentNonce, ID: $hashHex")
                event.id = hashHex
                NostrCryptoUtils.signEvent(event, privateKeyHex)
                return event
            }

            currentNonce++
            
            // Log progress occasionally to avoid silent freezing
            if (currentNonce % 500_000L == 0L) {
                onProgress("Mining... checked $currentNonce hashes")
            }
        }
    }

    /**
     * Counts the number of leading zero bits in a hex string correctly.
     * Every '0' character is 4 zero bits.
     */
    fun countLeadingZeroBits(hex: String): Int {
        var zeroBits = 0
        for (char in hex) {
            when (char) {
                '0' -> zeroBits += 4
                '1' -> return zeroBits + 3
                '2', '3' -> return zeroBits + 2
                '4', '5', '6', '7' -> return zeroBits + 1
                else -> return zeroBits // '8' and above have the leading bit as 1
            }
        }
        return zeroBits
    }
}
