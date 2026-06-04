package com.tvcs.homematic

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

/**
 * Handles GitHub-based update checks and APK download + installation.
 * Works without Retrofit/Hilt — uses only OkHttp (already a dependency) or
 * plain HttpURLConnection for the lightweight API call.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val GITHUB_API =
        "https://api.github.com/repos/Ub1Catcrush/homematic_launcher_v2/releases/latest"

    data class UpdateInfo(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val releaseNotes: String?,
        val downloadUrl: String?
    )

    suspend fun checkForUpdates(context: Context): Result<UpdateInfo> =
        withContext(Dispatchers.IO) {
            try {
                val con = URL(GITHUB_API).openConnection() as java.net.HttpURLConnection
                con.apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    connectTimeout = 15_000
                    readTimeout    = 15_000
                }
                val body = try {
                    con.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    con.disconnect()
                }

                val release = GithubRelease.fromJson(JSONObject(body))
                val latestVersion  = release.tagName.removePrefix("v")
                val currentVersion = getAppVersion(context)
                val hasUpdate      = isNewerVersion(currentVersion, latestVersion)
                val apkAsset       = release.assets.firstOrNull {
                    it.name.equals("app-release-signed.apk", ignoreCase = true)
                }

                Result.success(
                    UpdateInfo(
                        hasUpdate     = hasUpdate,
                        latestVersion = latestVersion,
                        releaseNotes  = release.body,
                        downloadUrl   = apkAsset?.downloadUrl
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                Result.failure(e)
            }
        }

    private fun getAppVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        val cur = current.split(".").mapNotNull { it.toIntOrNull() }
        val lat = latest.split(".").mapNotNull  { it.toIntOrNull() }
        val len = maxOf(cur.size, lat.size)
        for (i in 0 until len) {
            val c = cur.getOrNull(i) ?: 0
            val l = lat.getOrNull(i) ?: 0
            if (l > c) return true
            if (c > l) return false
        }
        return false
    }

    fun checkInstallPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.packageManager.canRequestPackageInstalls()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not open settings", e)
                }
                return false
            }
        }
        return true
    }

    fun downloadAndInstall(context: Context, url: String, fileName: String) {
        if (!checkInstallPermission(context)) {
            Toast.makeText(context, "Bitte erlaube der App, Updates zu installieren.", Toast.LENGTH_LONG).show()
            return
        }

        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (destination.exists()) destination.delete()

        val request = DownloadManager.Request(url.toUri())
            .setTitle("HomeMatic Launcher Update")
            .setDescription("Version $fileName wird heruntergeladen…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(destination))
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = downloadManager.enqueue(request)

        val onComplete = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id == downloadId) {
                    installApk(ctx, destination)
                    ctx.unregisterReceiver(this)
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            onComplete,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun installApk(context: Context, file: File) {
        if (!file.exists()) {
            Log.e(TAG, "APK not found: ${file.absolutePath}")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            Toast.makeText(context, "Installation fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
