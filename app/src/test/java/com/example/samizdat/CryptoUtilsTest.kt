package com.example.samizdat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class CryptoUtilsTest {

    @Test
    fun verifySignature_withValidSignature_returnsTrue() {
        // 1. Generate a real Ed25519 key pair (Java 15+ supports "Ed25519", checking fallback)
        val kpg = try {
            KeyPairGenerator.getInstance("Ed25519")
        } catch (e: Exception) {
             // Fallback for environments where Ed25519 might be named differently or need provider
             // Since we are running in full JDK environment (likely 17+), this should work.
             // If not, we skip or use RSA for testing logic flow (though CryptoUtils is hardcoded for Ed25519)
             throw e
        }
        val kp = kpg.generateKeyPair()
        val pubKeyBytes = kp.public.encoded
        
        // 2. Sign some data
        val data = "Hello Samizdat"
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(kp.private)
        signer.update(data.toByteArray())
        val signatureBytes = signer.sign()
        val signatureBase64 = Base64.getEncoder().encodeToString(signatureBytes)

        // 3. Verify using our Utils
        val result = CryptoUtils.verifySignature(data, signatureBase64, pubKeyBytes)
        
        assertTrue("Signature should be valid", result)
    }

    @Test
    fun verifySignature_withInvalidSignature_returnsFalse() {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()
        
        val data = "Important Data"
        val fakeSignature = Base64.getEncoder().encodeToString("InvalidBytes".toByteArray())
        
        val result = CryptoUtils.verifySignature(data, fakeSignature, kp.public.encoded)
        
        assertFalse("Signature should be invalid", result)
    }
    
    @Test
    fun verifySignature_withTamperedData_returnsFalse() {
        val kpg = KeyPairGenerator.getInstance("Ed25519")
        val kp = kpg.generateKeyPair()
        
        val data = "Original Data"
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(kp.private)
        signer.update(data.toByteArray())
        val signatureBase64 = Base64.getEncoder().encodeToString(signer.sign())

        val result = CryptoUtils.verifySignature("Tampered Data", signatureBase64, kp.public.encoded)
        
        assertFalse("Signature should be invalid for tampered data", result)
    }
}
