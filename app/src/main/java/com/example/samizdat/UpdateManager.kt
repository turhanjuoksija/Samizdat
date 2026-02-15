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
                
                if (CryptoUtils.verifyDeveloperSignature(dataToVerify, expectedSignature)) {
                    Log.i("UpdateManager", "Signature VALID. Starting install.")
                    installApk(file)
                } else {
                    Log.e("UpdateManager", "Signature INVALID! Deleting file.")
                    file.delete()
                    throw SecurityException("Invalid signature on update payload.")
                }
            } catch (e: Exception) {
                Log.e("UpdateManager", "Update failed", e)
                file.delete()
            }
        }
    }

    private fun downloadFileOverTor(urlStr: String, destination: File) {
        val socksPort = torManager.socksPort.value ?: throw IllegalStateException("Tor SOCKS port not available yet")
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val url = URL(urlStr)
        val conn = url.openConnection(proxy) as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
             throw java.io.IOException("Download failed: HTTP ${conn.responseCode}")
        }
        
        conn.inputStream.use { input ->
            FileOutputStream(destination).use { output ->
                input.copyTo(output)
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
        }
        context.startActivity(intent)
    }
}
