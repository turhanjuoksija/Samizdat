package com.example.samizdat

import android.util.Log
import fr.acinq.secp256k1.Hex
import fr.acinq.secp256k1.Secp256k1
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections

/**
 * Represents a standard NIP-01 Nostr Event.
 */
data class NostrEvent(
    var id: String = "",
    var pubkey: String = "",
    var created_at: Long = 0L,
    var kind: Int = 0,
    var tags: List<List<String>> = emptyList(),
    var content: String = "",
    var sig: String = ""
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("pubkey", pubkey)
        json.put("created_at", created_at)
        json.put("kind", kind)
        val tagsArray = JSONArray()
        tags.forEach { tagList ->
            val tagArray = JSONArray()
            tagList.forEach { tagArray.put(it) }
            tagsArray.put(tagArray)
        }
        json.put("tags", tagsArray)
        json.put("content", content)
        json.put("sig", sig)
        return json
    }
}

object NostrCryptoUtils {
    private const val TAG = "NostrCryptoUtils"
    private val secp256k1 = Secp256k1.get()

    /**
     * Generates a new random private key (32 bytes).
     * Returns a pair of (PrivateKeyHex, PublicKeyHex)
     */
    fun generateKeyPair(): Pair<String, String> {
        val privateKey = ByteArray(32)
        val random = SecureRandom()
        do {
            random.nextBytes(privateKey)
        } while (!secp256k1.secKeyVerify(privateKey))
        
        // Nostr uses 32-byte x-only public keys for Schnorr signatures (BIP340)
        val pubKeyFull = secp256k1.pubkeyCreate(privateKey)
        val pubKeyXOnly = pubKeyFull.copyOfRange(1, 33) 

        return Pair(Hex.encode(privateKey), Hex.encode(pubKeyXOnly))
    }

    /**
     * Serializes the event for hashing according to NIP-01:
     * [0, "pubkey", created_at, kind, [["tag", "value"]], "content"]
     */
    fun serializeEvent(event: NostrEvent): String {
        val array = JSONArray()
        array.put(0) // reserved
        array.put(event.pubkey)
        array.put(event.created_at)
        array.put(event.kind)

        val tagsArray = JSONArray()
        event.tags.forEach { tagList ->
            val tagArray = JSONArray()
            tagList.forEach { tagArray.put(it) }
            tagsArray.put(tagArray)
        }
        array.put(tagsArray)
        array.put(event.content)

        // Nostr serialization requires no spaces after commas
        return array.toString().replace("\\/".toRegex(), "/")
    }

    /**
     * Calculates the SHA-256 hash of the serialized event.
     */
    fun calculateId(event: NostrEvent): String {
        val serialized = serializeEvent(event)
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(serialized.toByteArray(Charsets.UTF_8))
        return Hex.encode(hash)
    }

    /**
     * Signs the event ID using Schnorr signature (NIP-01 / BIP340)
     */
    fun signEvent(event: NostrEvent, privateKeyHex: String) {
        try {
            val privateKey = Hex.decode(privateKeyHex)
            event.id = calculateId(event)
            val msgHash = Hex.decode(event.id)
            
            // BIP340 Schnorr signature
            // secp256k1-kmp requires a 32-byte random aux data to protect against side-channel attacks
            val auxRand = ByteArray(32)
            SecureRandom().nextBytes(auxRand)
            
            val signature = secp256k1.signSchnorr(msgHash, privateKey, auxRand)
            event.sig = Hex.encode(signature)
        } catch (e: Exception) {
            Log.e(TAG, "Error signing Nostr event", e)
        }
    }
}
