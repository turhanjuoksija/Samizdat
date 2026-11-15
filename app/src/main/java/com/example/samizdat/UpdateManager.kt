package com.example.samizdat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Downloads and verifies peer-to-peer app updates (DEVELOPMENT_NOTES §20).
 *
 * Three locks, all of which must pass before the user is asked to install:
 *  1. The [UpdateManifest] carries valid developer signatures (checked by the caller BEFORE download).
 *  2. The downloaded file has exactly the signed size and SHA-256 (download aborts as soon as it
 *     exceeds the signed size).
 *  3. The APK's signing certificate matches the installed app (Android enforces this again at install).
 */
class UpdateManager(
    private val context: Context,
    private val torManager: SamizdatTorManager
) {

    val currentVersionCode: Int = try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            pInfo.versionCode
        }
    } catch (_: Exception) { 0 }

    val packageName: String = context.packageName

    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    // ---------------- Manifest persistence ----------------

    /** Persists a signature-verified manifest so the phone can seed it after installing (and across restarts). */
    fun storeManifest(manifest: UpdateManifest) {
        prefs.edit().putString(KEY_MANIFEST, manifest.toStorageJson()).apply()
    }

    fun loadStoredManifest(): UpdateManifest? = UpdateManifest.fromStorageJson(prefs.getString(KEY_MANIFEST, null))

    // ---------------- Verified download ----------------

    /**
     * Downloads the APK described by [manifest] from the first working [sources] (one at a time),
     * verifying size, SHA-256 and signing certificate. Resumes partial downloads (HTTP Range).
     *
     * The caller MUST have verified [manifest] signatures before calling this.
     */
    suspend fun downloadVerified(
        manifest: UpdateManifest,
        sources: List<String>,
        onProgress: (bytesDone: Long, bytesTotal: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        require(manifest.version > currentVersionCode) { "Not newer than installed version" }
        require(manifest.size in 1..UpdateManifest.MAX_APK_BYTES) { "Invalid size" }
        if (sources.isEmpty()) throw java.io.IOException("No peer is sharing v${manifest.version} yet")

        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Remove leftovers of other versions
        updateDir.listFiles()?.forEach { f ->
            if (!f.name.startsWith("update_v${manifest.version}.")) f.delete()
        }
        val finalFile = File(updateDir, "update_v${manifest.version}.apk")
        val partFile = File(updateDir, "update_v${manifest.version}.apk.part")

        if (finalFile.exists() && finalFile.length() == manifest.size &&
            UpdateManifest.sha256Hex(finalFile) == manifest.sha256
        ) {
            checkSigningCertificate(finalFile)
            return@withContext finalFile
        }
        finalFile.delete()

        var lastError: Exception? = null
        for (source in sources) {
            try {
                Log.i(TAG, "Downloading v${manifest.version} from $source (resume at ${partFile.length()})")
                downloadOverTor(source, partFile, manifest.size, onProgress)

                if (partFile.length() != manifest.size) {
                    throw java.io.IOException("Incomplete download (${partFile.length()} / ${manifest.size} bytes)")
                }
                val hash = UpdateManifest.sha256Hex(partFile)
                if (hash != manifest.sha256) {
                    partFile.delete() // corrupt or malicious content: never resume from it
                    throw SecurityException("SHA-256 mismatch: file does not match the signed release")
                }
                checkSigningCertificate(partFile)
                if (!partFile.renameTo(finalFile)) throw java.io.IOException("Could not finalize download")
                Log.i(TAG, "v${manifest.version} downloaded and verified (size, SHA-256, certificate)")
                return@withContext finalFile
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SecurityException) {
                Log.e(TAG, "Verification failed for $source: ${e.message}")
                partFile.delete()
                lastError = e
            } catch (e: Exception) {
                Log.w(TAG, "Download from $source failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: java.io.IOException("Download failed")
    }

    private fun downloadOverTor(url: String, part: File, expectedSize: Long, onProgress: (Long, Long) -> Unit) {
        val socksPort = torManager.socksPort.value ?: throw IllegalStateException("Tor is not ready yet")
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))

        val client = okhttp3.OkHttpClient.Builder()
            .proxy(proxy)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    // Never resolve .onion locally; Tor's SOCKS proxy resolves it
                    if (hostname.endsWith(".onion")) throw java.net.UnknownHostException("Resolved by Tor: $hostname")
                    return okhttp3.Dns.SYSTEM.lookup(hostname)
                }
            })
            .connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT, okhttp3.ConnectionSpec.MODERN_TLS))
            // Onion circuit building can take 60-90 s
            .connectTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        var offset = if (part.exists()) part.length() else 0L
        if (offset >= expectedSize) {
            part.delete()
            offset = 0L
        }

        val request = okhttp3.Request.Builder().url(url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()

        client.newCall(request).execute().use { response ->
            val append = when (response.code) {
                206 -> true
                200 -> false // server ignored Range: start over
                503 -> throw java.io.IOException("Peer is busy or has reached its sharing limit")
                404 -> throw java.io.IOException("Peer is not sharing this version")
                else -> throw java.io.IOException("HTTP ${response.code}")
            }
            if (!append) offset = 0L
            val body = response.body ?: throw java.io.IOException("Empty response")

            var written = offset
            onProgress(written, expectedSize)
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var lastReport = written
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        written += read
                        // Lock 2 (early): never accept more than the signed size
                        if (written > expectedSize) {
                            throw SecurityException("Peer sent more data than the signed size")
                        }
                        output.write(buffer, 0, read)
                        if (written - lastReport >= 256 * 1024) {
                            onProgress(written, expectedSize)
                            lastReport = written
                        }
                    }
                }
            }
            onProgress(written, expectedSize)
        }
    }

    /**
     * Lock 3: the downloaded APK must be signed by the same certificate as the installed app
     * (or a newer certificate that has a proof-of-rotation from it, APK Signature Scheme v3).
     * Fails closed on a definite mismatch. If the platform cannot read the archive's certificates,
     * we rely on Android's own mandatory check at install time.
     */
    @Suppress("DEPRECATION")
    private fun checkSigningCertificate(apk: File) {
        val pm = context.packageManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: throw SecurityException("Downloaded file is not a valid APK")
            if (archive.packageName != context.packageName) throw SecurityException("APK has a different package name")
            val archiveInfo = archive.signingInfo
            val installedInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            if (archiveInfo == null || installedInfo == null) {
                Log.w(TAG, "Signing info unavailable; relying on Android install-time verification")
                return
            }
            val archiveCerts = (if (archiveInfo.hasMultipleSigners()) archiveInfo.apkContentsSigners
                else archiveInfo.signingCertificateHistory)?.toSet().orEmpty()
            val installedCerts = installedInfo.apkContentsSigners?.toSet().orEmpty()
            if (installedCerts.isEmpty() || !archiveCerts.containsAll(installedCerts)) {
                throw SecurityException("APK signing certificate does not match the installed app")
            }
        } else {
            val archive = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
                ?: throw SecurityException("Downloaded file is not a valid APK")
            if (archive.packageName != context.packageName) throw SecurityException("APK has a different package name")
            val installed = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            val archiveSigs = archive.signatures
            if (archiveSigs == null || installed == null) {
                Log.w(TAG, "Signatures unavailable; relying on Android install-time verification")
                return
            }
            if (archiveSigs.toSet() != installed.toSet()) {
                throw SecurityException("APK signing certificate does not match the installed app")
            }
        }
    }

    // ---------------- Install ----------------

    /** Opens the system installer. Android always asks the user to confirm. */
    fun installApk(file: File) {
        val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start install intent", e)
            @Suppress("DEPRECATION")
            val fallbackIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = contentUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback install intent also failed", e2)
            }
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
        private const val KEY_MANIFEST = "verified_manifest"
    }
}
