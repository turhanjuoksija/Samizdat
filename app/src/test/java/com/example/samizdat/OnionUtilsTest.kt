package com.example.samizdat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnionUtilsTest {

    @Test
    fun ensureOnionSuffix_addsSuffixToLongHash() {
        // v3 addresses are 56 chars
        val validHash = "v2c77fgs473333333333333333333333333333333333333333333333"
        val expected = "$validHash.onion"
        
        assertEquals(expected, OnionUtils.ensureOnionSuffix(validHash))
    }

    @Test
    fun ensureOnionSuffix_doesNotAddSuffixIfPresent() {
        val address = "testaddress.onion"
        assertEquals(address, OnionUtils.ensureOnionSuffix(address))
    }

    @Test
    fun ensureOnionSuffix_doesNotAddSuffixToShortString() {
        val short = "local"
        assertEquals(short, OnionUtils.ensureOnionSuffix(short))
    }

    @Test
    fun isOnion_detectsSuffix() {
        assertTrue(OnionUtils.isOnion("something.onion"))
        assertFalse(OnionUtils.isOnion("google.com"))
        assertFalse(OnionUtils.isOnion("plainstring"))
    }
}
