package com.example.samizdat

object OnionUtils {
    /**
     * Ensures that a potential Tor address has the .onion suffix.
     * Tor v3 addresses are 56 characters long before the suffix.
     */
    fun ensureOnionSuffix(address: String): String {
        val trimmed = address.trim()
        if (trimmed.isEmpty()) return trimmed
        
        // If it looks like a Tor v3 hash (long, no dots) and doesn't have .onion
        if (!trimmed.contains(".") && trimmed.length >= 50) {
            return "$trimmed.onion"
        }
        return trimmed
    }

    /**
     * Checks if a string looks like a Tor address (ends with .onion).
     */
    fun isOnion(address: String): Boolean {
        return address.trim().endsWith(".onion")
    }
}
