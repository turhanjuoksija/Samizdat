package com.example.samizdat

/**
 * MessageValidator — Pure validation utility for incoming network messages.
 * All functions are stateless and have no Android framework dependencies,
 * making them easily unit-testable.
 */
object MessageValidator {

    // --- String Limits ---
    const val MAX_NICKNAME_LENGTH = 50
    const val MAX_INFO_LENGTH = 500
    const val MAX_CONTENT_LENGTH = 2000
    const val MAX_GRID_ID_LENGTH = 30
    const val MAX_URL_LENGTH = 500

    // --- Array Limits ---
    const val MAX_ROUTE_POINTS = 500
    const val MAX_ROUTE_GRIDS = 100

    // --- Numeric Limits ---
    const val MAX_TTL_SECONDS = 86_400   // 24 hours
    const val MIN_TTL_SECONDS = 60       // 1 minute
    const val MAX_SEATS = 20

    // --- Time ---
    const val MAX_TIMESTAMP_DRIFT_MS = 86_400_000L  // 24h into the future

    // --- Known message types ---
    private val KNOWN_TYPES = setOf(
        "text", "status", "ride_request", "ride_accept", "ride_decline",
        "dht_store", "vouch", "APP_UPDATE"
    )

    // --- Known roles ---
    private val KNOWN_ROLES = setOf("DRIVER", "PASSENGER", "NONE")

    /**
     * Truncate a string to [maxLength] characters.
     * Also strips control characters (except newline/tab) to prevent log injection.
     */
    fun sanitizeString(input: String, maxLength: Int): String {
        val cleaned = input.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
        return if (cleaned.length > maxLength) cleaned.substring(0, maxLength) else cleaned
    }

    fun isValidLatitude(lat: Double): Boolean = lat in -90.0..90.0

    fun isValidLongitude(lon: Double): Boolean = lon in -180.0..180.0

    /**
     * Validate a coordinate pair. Returns null if either value is out of range.
     */
    fun validateCoordinate(lat: Double?, lon: Double?): Pair<Double, Double>? {
        if (lat == null || lon == null) return null
        if (!isValidLatitude(lat) || !isValidLongitude(lon)) return null
        return lat to lon
    }

    /**
     * Validate grid ID format: must start with "RG-" and be within length limit.
     */
    fun isValidGridId(gridId: String): Boolean {
        return gridId.length in 3..MAX_GRID_ID_LENGTH && gridId.startsWith("RG-")
    }

    /**
     * Validate onion address: either already has .onion suffix, or is 56+ chars (v3 hash).
     */
    fun isValidOnionAddress(addr: String): Boolean {
        if (addr.isBlank()) return false
        if (addr.endsWith(".onion")) return addr.length > 6
        // v3 onion address hash is 56 chars
        return addr.length >= 56 && addr.all { it.isLetterOrDigit() }
    }

    /**
     * Check if timestamp is reasonable: not zero, not too far in the future.
     */
    fun isValidTimestamp(ts: Long): Boolean {
        if (ts <= 0) return false
        val now = System.currentTimeMillis()
        return ts <= now + MAX_TIMESTAMP_DRIFT_MS
    }

    fun clampTtl(ttl: Int): Int = ttl.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)

    fun clampSeats(seats: Int): Int = seats.coerceIn(0, MAX_SEATS)

    fun isKnownMessageType(type: String): Boolean = type in KNOWN_TYPES

    fun isKnownRole(role: String): Boolean = role in KNOWN_ROLES

    /**
     * Validate a URL for update messages: must be http(s) and contain .onion
     */
    fun isValidUpdateUrl(url: String): Boolean {
        if (url.length > MAX_URL_LENGTH) return false
        return (url.startsWith("http://") || url.startsWith("https://")) && url.contains(".onion")
    }

    /**
     * Sanitize a role string: return "NONE" if not recognized.
     */
    fun sanitizeRole(role: String): String = if (isKnownRole(role)) role else "NONE"
}
