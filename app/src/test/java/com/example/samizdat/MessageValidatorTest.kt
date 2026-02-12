package com.example.samizdat

import org.junit.Assert.*
import org.junit.Test

class MessageValidatorTest {

    // ========== sanitizeString ==========

    @Test
    fun sanitizeString_passesShortStrings() {
        assertEquals("hello", MessageValidator.sanitizeString("hello", 50))
    }

    @Test
    fun sanitizeString_truncatesLongStrings() {
        val long = "a".repeat(200)
        val result = MessageValidator.sanitizeString(long, 50)
        assertEquals(50, result.length)
    }

    @Test
    fun sanitizeString_stripsControlCharacters() {
        val input = "hello\u0000world\u0007test"
        val result = MessageValidator.sanitizeString(input, 100)
        assertEquals("helloworldtest", result)
    }

    @Test
    fun sanitizeString_preservesNewlinesAndTabs() {
        val input = "line1\nline2\ttab"
        val result = MessageValidator.sanitizeString(input, 100)
        // newline (\n = 0x0A) and tab (\t = 0x09) should be preserved
        assertEquals("line1\nline2\ttab", result)
    }

    @Test
    fun sanitizeString_emptyString() {
        assertEquals("", MessageValidator.sanitizeString("", 50))
    }

    // ========== isValidLatitude ==========

    @Test
    fun isValidLatitude_validRange() {
        assertTrue(MessageValidator.isValidLatitude(0.0))
        assertTrue(MessageValidator.isValidLatitude(90.0))
        assertTrue(MessageValidator.isValidLatitude(-90.0))
        assertTrue(MessageValidator.isValidLatitude(60.1699))
    }

    @Test
    fun isValidLatitude_invalidRange() {
        assertFalse(MessageValidator.isValidLatitude(90.001))
        assertFalse(MessageValidator.isValidLatitude(-90.001))
        assertFalse(MessageValidator.isValidLatitude(9999.0))
    }

    // ========== isValidLongitude ==========

    @Test
    fun isValidLongitude_validRange() {
        assertTrue(MessageValidator.isValidLongitude(0.0))
        assertTrue(MessageValidator.isValidLongitude(180.0))
        assertTrue(MessageValidator.isValidLongitude(-180.0))
        assertTrue(MessageValidator.isValidLongitude(24.9384))
    }

    @Test
    fun isValidLongitude_invalidRange() {
        assertFalse(MessageValidator.isValidLongitude(180.001))
        assertFalse(MessageValidator.isValidLongitude(-180.001))
        assertFalse(MessageValidator.isValidLongitude(-10000.0))
    }

    // ========== validateCoordinate ==========

    @Test
    fun validateCoordinate_validPair() {
        val result = MessageValidator.validateCoordinate(60.17, 24.94)
        assertNotNull(result)
        assertEquals(60.17, result!!.first, 0.001)
        assertEquals(24.94, result.second, 0.001)
    }

    @Test
    fun validateCoordinate_invalidLat() {
        assertNull(MessageValidator.validateCoordinate(999.0, 24.94))
    }

    @Test
    fun validateCoordinate_nullInput() {
        assertNull(MessageValidator.validateCoordinate(null, 24.94))
        assertNull(MessageValidator.validateCoordinate(60.17, null))
        assertNull(MessageValidator.validateCoordinate(null, null))
    }

    // ========== isValidGridId ==========

    @Test
    fun isValidGridId_valid() {
        assertTrue(MessageValidator.isValidGridId("RG-3345-681"))
        assertTrue(MessageValidator.isValidGridId("RG-0-0"))
    }

    @Test
    fun isValidGridId_invalid() {
        assertFalse(MessageValidator.isValidGridId(""))
        assertFalse(MessageValidator.isValidGridId("GRID-123"))
        assertFalse(MessageValidator.isValidGridId("RG"))
        assertFalse(MessageValidator.isValidGridId("RG-" + "x".repeat(30)))
    }

    // ========== isValidOnionAddress ==========

    @Test
    fun isValidOnionAddress_validWithSuffix() {
        assertTrue(MessageValidator.isValidOnionAddress("abc123.onion"))
    }

    @Test
    fun isValidOnionAddress_validV3Hash() {
        val v3 = "a".repeat(56)
        assertTrue(MessageValidator.isValidOnionAddress(v3))
    }

    @Test
    fun isValidOnionAddress_invalid() {
        assertFalse(MessageValidator.isValidOnionAddress(""))
        assertFalse(MessageValidator.isValidOnionAddress("short"))
        assertFalse(MessageValidator.isValidOnionAddress(".onion"))
    }

    // ========== isValidTimestamp ==========

    @Test
    fun isValidTimestamp_currentTime() {
        assertTrue(MessageValidator.isValidTimestamp(System.currentTimeMillis()))
    }

    @Test
    fun isValidTimestamp_zero() {
        assertFalse(MessageValidator.isValidTimestamp(0))
    }

    @Test
    fun isValidTimestamp_farFuture() {
        val farFuture = System.currentTimeMillis() + 200_000_000L // ~2.3 days
        assertFalse(MessageValidator.isValidTimestamp(farFuture))
    }

    @Test
    fun isValidTimestamp_recentPast() {
        val tenMinutesAgo = System.currentTimeMillis() - 600_000
        assertTrue(MessageValidator.isValidTimestamp(tenMinutesAgo))
    }

    // ========== clampTtl ==========

    @Test
    fun clampTtl_withinRange() {
        assertEquals(3600, MessageValidator.clampTtl(3600))
    }

    @Test
    fun clampTtl_tooLow() {
        assertEquals(MessageValidator.MIN_TTL_SECONDS, MessageValidator.clampTtl(10))
    }

    @Test
    fun clampTtl_tooHigh() {
        assertEquals(MessageValidator.MAX_TTL_SECONDS, MessageValidator.clampTtl(999999))
    }

    // ========== clampSeats ==========

    @Test
    fun clampSeats_withinRange() {
        assertEquals(4, MessageValidator.clampSeats(4))
    }

    @Test
    fun clampSeats_negative() {
        assertEquals(0, MessageValidator.clampSeats(-5))
    }

    @Test
    fun clampSeats_tooHigh() {
        assertEquals(MessageValidator.MAX_SEATS, MessageValidator.clampSeats(100))
    }

    // ========== isKnownMessageType ==========

    @Test
    fun isKnownMessageType_validTypes() {
        assertTrue(MessageValidator.isKnownMessageType("text"))
        assertTrue(MessageValidator.isKnownMessageType("status"))
        assertTrue(MessageValidator.isKnownMessageType("dht_store"))
        assertTrue(MessageValidator.isKnownMessageType("vouch"))
        assertTrue(MessageValidator.isKnownMessageType("APP_UPDATE"))
    }

    @Test
    fun isKnownMessageType_unknownType() {
        assertFalse(MessageValidator.isKnownMessageType("evil_exploit"))
        assertFalse(MessageValidator.isKnownMessageType(""))
    }

    // ========== sanitizeRole ==========

    @Test
    fun sanitizeRole_validRoles() {
        assertEquals("DRIVER", MessageValidator.sanitizeRole("DRIVER"))
        assertEquals("PASSENGER", MessageValidator.sanitizeRole("PASSENGER"))
        assertEquals("NONE", MessageValidator.sanitizeRole("NONE"))
    }

    @Test
    fun sanitizeRole_invalidRole() {
        assertEquals("NONE", MessageValidator.sanitizeRole("ADMIN"))
        assertEquals("NONE", MessageValidator.sanitizeRole(""))
    }

    // ========== isValidUpdateUrl ==========

    @Test
    fun isValidUpdateUrl_valid() {
        assertTrue(MessageValidator.isValidUpdateUrl("http://abc123.onion/update.apk"))
        assertTrue(MessageValidator.isValidUpdateUrl("https://something.onion/path/to/file"))
    }

    @Test
    fun isValidUpdateUrl_invalid() {
        assertFalse(MessageValidator.isValidUpdateUrl(""))
        assertFalse(MessageValidator.isValidUpdateUrl("ftp://evil.com/malware"))
        assertFalse(MessageValidator.isValidUpdateUrl("http://clearnet.com/not-onion"))
        assertFalse(MessageValidator.isValidUpdateUrl("http://" + "a".repeat(500) + ".onion"))
    }
}
