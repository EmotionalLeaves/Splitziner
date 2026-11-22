package io.github.emotionalleaves.splitziner

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A release on GitHub that has an APK to install. */
class Release(val version: String, val title: String, val notes: String, val apkUrl: String, val apkSize: Long)

/**
 * Updates from the project's GitHub releases. Every release is signed with the same key, so Android
 * installs it over the current app and keeps the projects.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/EmotionalLeaves/Splitziner/releases/latest"

    /** How often the app looks on its own: at most once a day, so it costs almost no data. */
    const val AUTO_INTERVAL_MS = 24L * 60 * 60 * 1000

    private fun prefs(c: Context) = c.getSharedPreferences("updates", Context.MODE_PRIVATE)
    fun autoCheck(c: Context) = prefs(c).getBoolean("auto", true)
    fun setAutoCheck(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto", on).apply()
    fun dueForAutoCheck(c: Context) = autoCheck(c) && System.currentTimeMillis() - prefs(c).getLong("last", 0) > AUTO_INTERVAL_MS

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000
        setRequestProperty("User-Agent", "Splitziner/${BuildConfig.VERSION_NAME}")
        instanceFollowRedirects = true
    }

    /** The latest release, or null when there is none with an APK. Throws when the network fails. */
    fun latest(c: Context): Release? {
        val conn = open(LATEST).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            if (conn.responseCode == 404) return null
            if (conn.responseCode != 200) error("GitHub answered ${conn.responseCode}")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            prefs(c).edit().putLong("last", System.currentTimeMillis()).apply()
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (!a.optString("name").endsWith(".apk")) continue
                return Release(o.getString("tag_name").removePrefix("v"), o.optString("name"), o.optString("body"), a.getString("browser_download_url"), a.optLong("size"))
            }
            return null
        } finally { conn.disconnect() }
    }

    fun isNewer(r: Release) = splitziner.update.Versions.isNewer(r.version, BuildConfig.VERSION_NAME)

    /** Downloads the APK, reporting progress from 0 to 1. Checks it is complete and is this app, newer than now. */
    fun download(c: Context, r: Release, progress: (Float) -> Unit): File {
        val dir = File(c.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "Splitziner-${r.version}.apk")
        val tmp = File(dir, "download.part")
        val conn = open(r.apkUrl)
        try {
            if (conn.responseCode != 200) error("The download answered ${conn.responseCode}")
            val total = if (r.apkSize > 0) r.apkSize else conn.contentLengthLong
            var got = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        output.write(buf, 0, n); got += n
                        if (total > 0) progress((got.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
            if (r.apkSize > 0 && got != r.apkSize) error("The download was incomplete ($got of ${r.apkSize} bytes)")
        } finally { conn.disconnect() }
        @Suppress("DEPRECATION")
        val info = c.packageManager.getPackageArchiveInfo(tmp.path, 0) ?: error("The downloaded file is not an app")
        if (info.packageName != c.packageName) error("The downloaded app is not Splitziner")
        tmp.renameTo(out)
        return out
    }

    /** Android asks once whether Splitziner may install apps; until then, this opens that setting. */
    fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(c: Context) {
        c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(c: Context, apk: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", apk)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
