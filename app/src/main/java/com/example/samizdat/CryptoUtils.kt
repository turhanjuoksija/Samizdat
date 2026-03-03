package com.example.samizdat

import java.util.Base64
import java.security.KeyStore
import java.security.Signature

object CryptoUtils {
    private const val ALIAS = "samizdat_rsa_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val SIGN_ALGO = "SHA256withRSA" // Universally supported on Android

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

    fun getPublicKey(): String? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val entry = keyStore.getEntry(ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (entry != null) {
                Base64.getEncoder().encodeToString(entry.certificate.publicKey.encoded)
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun verifySignature(data: String, signatureBase64: String, publicKeyEncoded: ByteArray): Boolean {
        return try {
            val kf = java.security.KeyFactory.getInstance("RSA")
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
    
    // Hardcoded Developer Public Key (X.509 Encoded RSA)
    // Corresponds to private_rsa.pem generated offline.
    // Replace this if you rotate keys!
    private const val DEVELOPER_PUBLIC_KEY = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAgutnS9/+j4KGsVwuZHFdBzjq68vmTnMaYBhNM+3swfWxT3SK4wkY65az6IWZNzJQZPE7ifIFLZGieTt5sXD4mxtd9yXK/A/WQ73W6DeBvNRlKw3xImhxQrbYwWb9V8WTdsMo5/PISrsBeh4cMCmjz1cmPZ2fVCMdbSY7GcgAUJnTAZDFloDjRCLeURAPNIVlhzQBhbjKDE/1ANl3kEsl1uniN8l/xbVzLikKny2AH83gKsmy09mbIDg0ihYsLob8dkedX1RnweWqF4/2ymO0qUX8GzW0jfb/r7t07kG3raNOvbOrfPnWzVlh4Xiatrq/H4z+j2OGl/kMIHNd0roRNQIDAQAB"

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
