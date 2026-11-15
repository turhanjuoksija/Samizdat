package com.example.samizdat

import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * A developer-signed description of one release APK (DEVELOPMENT_NOTES §20).
 *
 * Security model: the manifest is verified BEFORE anything is downloaded. Because it pins the exact
 * size and SHA-256 of the APK, the download source no longer matters: any peer can serve the file,
 * and a wrong file simply fails the hash check.
 *
 *  - sig  (v1, legacy):  SHA256withRSA over "UPDATE:<versionCode>:<sha256>"
 *    Still produced and relayed so that older app versions can verify updates served by peers.
 *  - sig2 (v2):          SHA256withRSA over "UPDATE2:<packageName>:<versionCode>:<size>:<sha256>"
 *    Required by this version. Also covers size and package name.
 *
 * Both are signed with the developer key (private_rsa.pem); the public key is in [CryptoUtils].
 */
data class UpdateManifest(
    val version: Int,
    val sha256: String,
    val size: Long,
    val sig: String,
    val sig2: String,
    /** Optional download source hint ("http://<onion>/update_v<version>.apk"). Not signed, not trusted. */
    val url: String? = null
) {
    fun v1Payload(): String = "UPDATE:$version:$sha256"
    fun v2Payload(packageName: String): String = "UPDATE2:$packageName:$version:$size:$sha256"

    fun isWellFormed(): Boolean =
        version > 0 &&
            size in 1..MAX_APK_BYTES &&
            SHA256_HEX.matches(sha256) &&
            sig.isNotEmpty() && sig.length <= MAX_SIG_CHARS &&
            sig2.isNotEmpty() && sig2.length <= MAX_SIG_CHARS

    /** Lock 1: both developer signatures must be valid. Cheap (RSA verify), safe to call on untrusted input. */
    fun verify(packageName: String): Boolean =
        isWellFormed() &&
            CryptoUtils.verifyDeveloperSignature(v2Payload(packageName), sig2) &&
            CryptoUtils.verifyDeveloperSignature(v1Payload(), sig)

    /** Same signed release (url ignored). */
    fun sameReleaseAs(other: UpdateManifest?): Boolean =
        other != null && other.version == version && other.sha256 == sha256 &&
            other.size == size && other.sig2 == sig2

    /**
     * Gossip format. Backward compatible with older app versions, which read only ver/url/sig
     * (and need a url to download from).
     */
    fun toGossipJson(sourceUrl: String?): String = JSONObject().apply {
        put("v", 1)
        put("type", "APP_UPDATE")
        put("ver", version)
        if (!sourceUrl.isNullOrEmpty()) put("url", sourceUrl)
        put("sig", sig)
        put("sha256", sha256)
        put("size", size)
        put("sig2", sig2)
    }.toString()

    fun toStorageJson(): String = JSONObject().apply {
        put("ver", version)
        put("sha256", sha256)
        put("size", size)
        put("sig", sig)
        put("sig2", sig2)
        if (!url.isNullOrEmpty()) put("url", url)
    }.toString()

    companion object {
        /** Hard cap for any update APK (storage/bandwidth exhaustion protection). */
        const val MAX_APK_BYTES = 100L * 1024 * 1024
        private const val MAX_SIG_CHARS = 2048
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        private val SOURCE_PATH = Regex("""^/update_v(\d+)\.apk$""")

        /**
         * Parses a manifest from a gossip message or Nostr-derived JSON.
         * Returns null for legacy announcements without sha256/size/sig2: those cannot be verified
         * before downloading and are therefore ignored (closes the "download first, verify later" hole).
         */
        fun fromJson(json: JSONObject): UpdateManifest? {
            val version = json.optInt("ver", -1)
            val sha = json.optString("sha256", "").trim().lowercase()
            val size = json.optLong("size", -1L)
            val sig = json.optString("sig", "").trim()
            val sig2 = json.optString("sig2", "").trim()
            val url = json.optString("url", "").trim().takeIf { it.isNotEmpty() }
            if (version <= 0 || sha.isEmpty() || size <= 0 || sig.isEmpty() || sig2.isEmpty()) return null
            val manifest = UpdateManifest(version, sha, size, sig, sig2, url)
            return if (manifest.isWellFormed()) manifest else null
        }

        fun fromStorageJson(raw: String?): UpdateManifest? =
            try { raw?.let { fromJson(JSONObject(it)) } } catch (_: Exception) { null }

        /** URL under which a seeding phone serves its own installed APK. */
        fun sourceUrlFor(onion: String, version: Int): String =
            "http://${OnionUtils.ensureOnionSuffix(onion)}/update_v$version.apk"

        /** Version requested by an HTTP path like "/update_v20.apk", or null. */
        fun versionFromPath(path: String): Int? =
            SOURCE_PATH.matchEntire(path.substringBefore('?'))?.groupValues?.get(1)?.toIntOrNull()

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = fis.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

