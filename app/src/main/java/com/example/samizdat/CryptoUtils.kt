package com.example.samizdat

import java.util.Base64
import java.security.KeyStore
import java.security.Signature

object CryptoUtils {
    private const val ALIAS = "samizdat_ed25519_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val SIGN_ALGO = "Ed25519" // Android 12+ standard

    fun signData(data: String): String? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: return null

            val s = Signature.getInstance(SIGN_ALGO)
            s.initSign(entry.privateKey)
            s.update(data.toByteArray())
            val sig = s.sign()
            Base64.getEncoder().encodeToString(sig)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun verifySignature(data: String, signatureBase64: String, publicKeyEncoded: ByteArray): Boolean {
        return try {
            // Ed25519 public key bytes can be used to reconstruct the key if they are in X.509 format
            // However, our publicKeyEncoded is likely X.509 due to Android KeyStore's getEncoded()
            // Ed25519 is supported via "Ed25519" algorithm in KeyFactory on Android 12+
            val kf = try {
                java.security.KeyFactory.getInstance("Ed25519")
            } catch (e: Exception) {
                java.security.KeyFactory.getInstance("EdDSA") // Try fallback
            }
            val pubKey = kf.generatePublic(java.security.spec.X509EncodedKeySpec(publicKeyEncoded))
            
            val s = Signature.getInstance(SIGN_ALGO)
            s.initVerify(pubKey)
            s.update(data.toByteArray())
            val sigBytes = Base64.getDecoder().decode(signatureBase64)
            s.verify(sigBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    // Hardcoded Developer Public Key (X.509 Encoded Ed25519)
    // Corresponds to private.pem generated offline.
    // Replace this if you rotate keys!
    private const val DEVELOPER_PUBLIC_KEY = "MCowBQYDK2VwAyEARxOs+xzrg8gzt3f/CykQ0zOPTmb3WHiUJGibETBTwAk="

    // ... (rest of simple sign/verify methods)

    /**
     * Verifies that the given data was signed by the Developer (Trust Anchor).
     * This is critical for the Update Mechanism.
     */
    fun verifyDeveloperSignature(data: String, signatureBase64: String): Boolean {
        return try {
            val pubKeyBytes = Base64.getDecoder().decode(DEVELOPER_PUBLIC_KEY)
            verifySignature(data, signatureBase64, pubKeyBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }



}
