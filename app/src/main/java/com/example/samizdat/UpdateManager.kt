package com.example.samizdat

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

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

    /**
     * Downloads the APK from an Onion URL using Tor, verifies the signature, and prompts install.
     */
    suspend fun downloadAndInstall(onionUrl: String, expectedSignature: String, version: Int) {
        // --- Downgrade Protection ---
        if (version <= currentVersionCode) {
            Log.w("UpdateManager", "Rejected update: offered version $version <= installed version $currentVersionCode")
            return
        }
        // --- End Downgrade Protection ---

        val fileName = "update_v$version.apk"
        val updateDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(updateDir, fileName)

        withContext(Dispatchers.IO) {
            try {
                Log.i("UpdateManager", "Starting download of v$version (current: v$currentVersionCode) from $onionUrl")
                downloadFileOverTor(onionUrl, file)
                
                Log.i("UpdateManager", "Download complete. Verifying signature...")
                val fileHash = hashFile(file)
                // The signature signs the HASH of the file, plus the version code, to prevent tampering.
                // Protocol: Signature = Sign( "UPDATE:$version:" + SHA256(File) )
                
                val dataToVerify = "UPDATE:$version:$fileHash"
                
                if (!CryptoUtils.verifyDeveloperSignature(dataToVerify, expectedSignature)) {
                    Log.e("UpdateManager", "Signature INVALID! Deleting file.")
                    file.delete()
                    throw SecurityException("Invalid signature on update payload.")
                }
                
                Log.i("UpdateManager", "Signature VALID. Starting install.")
            } catch (e: Exception) {
                Log.e("UpdateManager", "Update failed", e)
                file.delete()
                throw e
            }
        }
        
        // MUST run on Main thread — starting Activities from background threads silently fails!
        withContext(Dispatchers.Main) {
            installApk(file)
        }
    }

    private fun downloadFileOverTor(urlStr: String, destination: File) {
        val socksPort = torManager.socksPort.value ?: throw IllegalStateException("Tor SOCKS port not available yet")
        
        // Java's HttpURLConnection tries to resolve .onion DNS locally and fails.
        // OkHttpClient correctly passes .onion resolution off to the SOCKS proxy, 
        // but we must explicitly tell it NOT to try local DNS first.
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        
        val client = okhttp3.OkHttpClient.Builder()
            .proxy(proxy)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<java.net.InetAddress> {
                    // Return empty/throw for .onion to force SOCKS proxy to do the resolution
                    if (hostname.endsWith(".onion")) {
                        throw java.net.UnknownHostException("Will be resolved by Tor: $hostname")
                    }
                    return okhttp3.Dns.SYSTEM.lookup(hostname)
                }
            })
            // Explicitly allow cleartext because .onion URLs use http://
            .connectionSpecs(listOf(okhttp3.ConnectionSpec.CLEARTEXT, okhttp3.ConnectionSpec.MODERN_TLS))
            // INCREASED to 120s: Tor hidden service circuit building can easily take 60-90s
            .connectTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            
        val request = okhttp3.Request.Builder()
            .url(urlStr)
            .build()
            
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("Download failed: HTTP ${response.code}")
            
            val body = response.body ?: throw java.io.IOException("Empty response body")
            body.byteStream().use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun hashFile(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun installApk(file: File) {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateManager", "Failed to start install intent", e)
            
            // Fallback for some device types
            val fallbackIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = contentUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                 Log.e("UpdateManager", "Fallback install intent also failed", e2)
            }
        }
    }
}
