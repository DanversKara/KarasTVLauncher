package com.moomoo.tvlauncherbase

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from GitHub, from Settings → Check for updates (and a quiet daily check).
 *
 * Where it looks, in order:
 *  1. the repo's latest GitHub Release — the first .apk attached to it;
 *  2. the repo's karas-tv/release/ folder — the karas-tv-X.Y.Z.apk with the highest version.
 * The APK is downloaded and handed to Android's installer, which asks to confirm.
 * Settings, cameras and shows are kept (it's an in-place update).
 */
object Updater {
    const val DEFAULT_REPO = "DanversKara/GoogleTV_Launchers"
    private const val FOLDER = "karas-tv/release"
    const val ADB_ALLOW = "adb shell appops set com.moomoo.tvlauncherbase REQUEST_INSTALL_PACKAGES allow"

    class Release(val version: String, val apkUrl: String, val notes: String?, val sizeBytes: Long, val from: String)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    fun installedVersion(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "0"
    } catch (e: Exception) { "0" }

    /** "2.8.10" > "2.8.2" > "2.8" ; ignores a leading "v" and anything after a space/dash. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").split(' ', '-', '_')[0]
            .split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    /** Version from a file name like "karas-tv-2.8.2.apk", or null. */
    fun versionFromName(name: String): String? =
        Regex("""(\d+(?:\.\d+)+)""").findAll(name).lastOrNull()?.value

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "KarasTV-Updater")
        try {
            val code = conn.responseCode
            if (code == 404) throw java.io.FileNotFoundException("404")
            if (code !in 200..299) throw java.io.IOException(
                if (code == 403) "GitHub limit reached — try again in an hour" else "GitHub answered HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }

    /** Finds the newest release (network — call off the main thread). */
    fun latest(c: Context): Release? {
        val repo = BasePrefs.updateRepo(c).trim().trim('/')
        val api = "https://api.github.com/repos/$repo"
        var best: Release? = null
        // 1) GitHub Releases
        try {
            val o = JSONObject(get("$api/releases/latest"))
            val assets = o.optJSONArray("assets") ?: JSONArray()
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                if (!name.endsWith(".apk", true)) continue
                val v = versionFromName(o.optString("tag_name")) ?: versionFromName(name) ?: continue
                best = Release(v, a.optString("browser_download_url"), o.optString("body").takeIf { it.isNotBlank() },
                    a.optLong("size"), "GitHub release ${o.optString("tag_name")}")
                break
            }
        } catch (e: java.io.FileNotFoundException) { /* no releases yet */ }
        // 2) the release folder in the repo
        try {
            val arr = JSONArray(get("$api/contents/$FOLDER"))
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val name = f.optString("name")
                if (!name.endsWith(".apk", true)) continue
                val v = versionFromName(name) ?: continue
                val url = f.optString("download_url").takeIf { it.startsWith("http") } ?: continue
                if (best == null || compare(v, best.version) > 0)
                    best = Release(v, url, null, f.optLong("size"), "$repo/$FOLDER/$name")
            }
        } catch (e: java.io.FileNotFoundException) { /* no folder */ }
        return best
    }

    // ------------------------------------------------------------------ UI
    /** Settings → Check for updates. */
    fun check(a: Activity) {
        if (busy) { Ui.toast(a, "Already checking…"); return }
        busy = true
        Ui.toast(a, "Checking GitHub for updates…")
        Thread {
            val res = try { Result.success(latest(a)) } catch (e: Exception) { Result.failure<Release?>(e) }
            busy = false
            BasePrefs.markUpdateCheck(a, System.currentTimeMillis())
            main.post {
                if (a.isFinishing) return@post
                val err = res.exceptionOrNull()
                val r = res.getOrNull()
                val mine = installedVersion(a)
                when {
                    err != null -> Ui.dialog(a).setTitle("Couldn't check for updates")
                        .setMessage("${ShowInfo.describeError(err)}\n\nLooking in: github.com/${BasePrefs.updateRepo(a)}")
                        .setPositiveButton(R.string.ok, null).show()
                    r == null -> Ui.dialog(a).setTitle("No releases found")
                        .setMessage("There's no APK in github.com/${BasePrefs.updateRepo(a)} (Releases or $FOLDER).")
                        .setPositiveButton(R.string.ok, null).show()
                    compare(r.version, mine) <= 0 -> Ui.dialog(a).setTitle("You're up to date ✓")
                        .setMessage("Installed: $mine\nNewest on GitHub: ${r.version}")
                        .setPositiveButton(R.string.ok, null).show()
                    else -> offer(a, r, mine)
                }
            }
        }.start()
    }

    private fun offer(a: Activity, r: Release, mine: String) {
        val size = if (r.sizeBytes > 0) " · ${"%.1f".format(r.sizeBytes / 1048576.0)} MB" else ""
        Ui.dialog(a).setTitle("Update to ${r.version}?")
            .setMessage("Installed: $mine\nAvailable: ${r.version}$size\nFrom: ${r.from}\n\n" +
                (r.notes?.take(700)?.let { "$it\n\n" } ?: "") +
                "Your settings, cameras, buttons and shows are kept. A backup is saved first.")
            .setPositiveButton("Download & install") { _, _ -> download(a, r) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** Quiet check once a day when the home screen opens; only speaks up when there's an update. */
    fun autoCheck(a: Activity) {
        if (!BasePrefs.autoUpdate(a) || busy) return
        if (System.currentTimeMillis() - BasePrefs.lastUpdateCheck(a) < 24 * 3600_000L) return
        BasePrefs.markUpdateCheck(a, System.currentTimeMillis())
        Thread {
            val r = try { latest(a) } catch (e: Exception) { null } ?: return@Thread
            val mine = installedVersion(a)
            if (compare(r.version, mine) > 0) main.post {
                if (!a.isFinishing) offer(a, r, mine)
            }
        }.start()
    }

    private fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    private fun askPermission(a: Activity) {
        val opened = try {
            a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
            true
        } catch (e: Exception) { false }
        Ui.dialog(a).setTitle("Allow ${Brand.name} to install updates")
            .setMessage((if (opened) "Turn on “Allow from this source” on the screen that just opened, then choose Check for updates again.\n\n" else "") +
                "If this TV has no such setting, run this once from a computer:\n$ADB_ALLOW")
            .setPositiveButton(R.string.ok, null).show()
    }

    private fun download(a: Activity, r: Release) {
        if (!canInstall(a)) { askPermission(a); return }
        if (busy) return
        busy = true
        Ui.toast(a, "Downloading ${r.version}…")
        Thread {
            try { Backup.writeNow(a, manual = false) } catch (e: Exception) {}
            val f = File(a.cacheDir, "update.apk")
            val err = try {
                val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "KarasTV-Updater")
                if (conn.responseCode !in 200..299) throw java.io.IOException("download failed (HTTP ${conn.responseCode})")
                conn.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it, 64 * 1024) } }
                conn.disconnect()
                if (f.length() < 50_000) throw java.io.IOException("the download was incomplete")
                install(a, f)
                null
            } catch (e: Exception) { e }
            busy = false
            main.post {
                if (err != null && !a.isFinishing) Ui.dialog(a).setTitle("Update failed")
                    .setMessage(ShowInfo.describeError(err)).setPositiveButton(R.string.ok, null).show()
            }
        }.start()
    }

    private fun install(c: Context, apk: File) {
        val pi = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) 0x02000000 /* MUTABLE */ else 0)
            val intent = Intent(c, UpdateReceiver::class.java).setAction("com.moomoo.tvlauncherbase.UPDATE_STATUS")
            s.commit(PendingIntent.getBroadcast(c, id, intent, flags).intentSender)
        }
    }
}

/** Gets the installer's answers: shows its confirm screen, and explains failures. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                try { c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> Ui.toast(c, "Updated ✓")
            else -> {
                val msg = when (st) {
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "This update was signed with a different key than the installed app. Save a backup, uninstall ${Brand.name}, then install the new APK."
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage for the update"
                    else -> "Update failed: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $st")
                }
                Ui.toast(c, msg)
            }
        }
    }
}
