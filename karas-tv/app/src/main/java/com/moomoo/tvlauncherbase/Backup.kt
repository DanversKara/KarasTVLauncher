package com.moomoo.tvlauncherbase

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File

/**
 * Backups that survive "Clear storage" and uninstalling.
 *
 * Each backup is its own file in the TV's shared Downloads folder, named with
 * the date and time it was made, so the newest is obvious:
 *   Download/Kara's TV/Kara's TV backup 2026-11-15 3.04 PM.json         (Back up now)
 *   Download/Kara's TV/Kara's TV backup 2026-11-15 3.04 PM (auto).json  (automatic)
 * Automatic backups run ~20 s after a change; within the same hour they update
 * (and rename) the newest automatic file instead of adding another. The newest
 * 10 automatic and 10 manual backups are kept.
 *
 * It holds every setting, camera, button mapping, theme, favorite, followed
 * show and Continue-watching entry. Writing: Android 10+ through MediaStore (no
 * permission needed); Android 8–9 with the storage permission. Reading after a
 * wipe: MediaStore when Android still counts the files as ours, else directly
 * (Android 8–10, or 11+ with "All files access"), else the user picks the file.
 * Older single-file backups (tv-launcher-backup*.json) are still found.
 */
object Backup {
    const val PREFIX = "Kara's TV backup "
    private const val AUTO = " (auto)"
    /** Single-file names used before 2.8.2 — still restorable. */
    const val NAME = "tv-launcher-backup.json"
    const val PREV = "tv-launcher-backup-previous.json"
    private const val FOLDER = "Kara's TV"
    /** Where 2.6 saved backups — still checked when restoring. */
    private const val OLD_FOLDER = "TV Launcher"
    private const val KEEP = 10
    const val REQ_PICK = 7311
    const val REQ_PERM = 7312

    /** True while restoring, so the restore itself doesn't trigger a backup. */
    @Volatile var suspended = false

    private val main = Handler(Looper.getMainLooper())
    private var pending: Context? = null
    private val runner = Runnable {
        val c = pending ?: return@Runnable
        pending = null
        Thread { writeNow(c, manual = false) }.start()
    }

    val location get() = "Download/$FOLDER/"

    /** One backup file found on the TV. */
    class Entry(val name: String, val modified: Long, val uri: Uri?, val file: File?) {
        val auto get() = name.contains(AUTO)
        val legacy get() = !name.startsWith(PREFIX)
    }

    /** Called after every settings change; writes ~20 s later (changes are batched). */
    fun schedule(c: Context) {
        if (suspended) return
        val app = c.applicationContext
        // Never overwrite a good backup with a freshly wiped, empty setup.
        if (!BasePrefs.setupDone(app) || !BasePrefs.autoBackup(app)) return
        pending = app
        main.removeCallbacks(runner)
        main.postDelayed(runner, 20_000)
    }

    private fun legacyDir(folder: String = FOLDER): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), folder)

    /** Can we use plain file paths in Downloads? */
    fun directAccess(c: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == 29 -> Environment.isExternalStorageLegacy() &&
            c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        else -> c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    fun needsPermission(c: Context) = Build.VERSION.SDK_INT < 29 &&
        c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    fun requestPermission(a: Activity) {
        if (needsPermission(a)) a.requestPermissions(
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), REQ_PERM)
    }

    /** "Kara's TV backup 2026-11-15 3.04 PM (auto).json" — no ":" (not allowed in file names). */
    fun fileName(at: Long, auto: Boolean): String =
        PREFIX + java.text.SimpleDateFormat("yyyy-MM-dd h.mm a", java.util.Locale.US).format(java.util.Date(at)) +
            (if (auto) AUTO else "") + ".json"

    /** Writes a dated backup. [manual] = "Back up now" (always a new file). */
    @Synchronized fun writeNow(c: Context, manual: Boolean = true): Boolean {
        val json = try { BasePrefs.exportJson(c) } catch (e: Exception) { return false }
        val now = System.currentTimeMillis()
        val name = fileName(now, auto = !manual)
        val mine = try { list(c).filter { !it.legacy } } catch (e: Exception) { emptyList() }
        // Automatic: update the newest automatic file if it's less than an hour old.
        val reuse = if (manual) null else mine.filter { it.auto }.maxByOrNull { it.modified }
            ?.takeIf { now - it.modified < 3600_000L }
        val ok = try {
            if (directAccess(c)) {
                val dir = legacyDir().apply { mkdirs() }
                val f = File(dir, name)
                val tmp = File(dir, ".backup.tmp"); tmp.writeText(json)
                reuse?.file?.takeIf { it != f }?.delete()
                tmp.renameTo(f) || run { f.writeText(json); true }
            } else if (Build.VERSION.SDK_INT >= 29) {
                val uri = reuse?.uri?.takeIf { rename(c, it, name) }
                writeMediaStore(c, name, json, uri)
            } else false
        } catch (e: Exception) { false }
        if (ok) {
            BasePrefs.markBackup(c, now)
            try { prune(c) } catch (e: Exception) {}
        }
        return ok
    }

    /** Keeps the newest [KEEP] automatic and [KEEP] manual backups. */
    private fun prune(c: Context) {
        val mine = list(c).filter { !it.legacy }
        for (group in listOf(mine.filter { it.auto }, mine.filter { !it.auto }))
            for (e in group.sortedByDescending { it.modified }.drop(KEEP)) delete(c, e)
    }

    private fun delete(c: Context, e: Entry) {
        try { e.file?.delete() } catch (x: Exception) {}
        try { e.uri?.let { c.contentResolver.delete(it, null, null) } } catch (x: Exception) {}
    }

    // ------------------------------------------------------------ MediaStore (Android 10+)
    private fun relPath() = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/"

    private fun rename(c: Context, uri: Uri, name: String): Boolean = try {
        c.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null) > 0
    } catch (e: Exception) { false }

    private fun writeMediaStore(c: Context, name: String, text: String, existing: Uri? = null): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val r = c.contentResolver
        val uri = existing ?: r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath())
        }) ?: return false
        r.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: return false
        return true
    }

    // ------------------------------------------------------------ finding
    /** Every backup we can see, newest first (dated ones, plus pre-2.8.2 single files). */
    fun list(c: Context): List<Entry> {
        val out = LinkedHashMap<String, Entry>()
        fun ours(n: String) = n.endsWith(".json", true) && (n.startsWith(PREFIX) || n == NAME || n == PREV)
        for (folder in listOf(FOLDER, OLD_FOLDER)) {
            try {
                legacyDir(folder).listFiles()?.forEach { f ->
                    if (f.isFile && ours(f.name) && f.canRead()) out["$folder/${f.name}"] = Entry(f.name, f.lastModified(), null, f)
                }
            } catch (e: Exception) {}
            if (Build.VERSION.SDK_INT >= 29) try {
                val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                c.contentResolver.query(coll,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("%/$folder/%"), null
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val n = cur.getString(1) ?: continue
                        if (!ours(n)) continue
                        val key = "$folder/$n"
                        val uri = ContentUris.withAppendedId(coll, cur.getLong(0))
                        val prev = out[key]
                        out[key] = Entry(n, prev?.modified ?: cur.getLong(2) * 1000L, uri, prev?.file)
                    }
                }
            } catch (e: Exception) {}
        }
        return out.values.sortedByDescending { it.modified }
    }

    fun read(c: Context, e: Entry): String? =
        (try { e.file?.takeIf { it.canRead() }?.readText() } catch (x: Exception) { null })
            ?: e.uri?.let { readUri(c, it) }

    /** The newest backup's contents without asking the user, or null. */
    fun find(c: Context): String? {
        for (e in list(c)) read(c, e)?.let { return it }
        return null
    }

    /** "Sun, Nov 15 · 3:04 PM" for a backup file. */
    fun label(e: Entry): String {
        val f = java.text.SimpleDateFormat("EEE, MMM d · h:mm a", java.util.Locale.getDefault())
        val kind = when { e.legacy -> "  (older backup)"; e.auto -> "  · automatic"; else -> "  · Back up now" }
        return f.format(java.util.Date(e.modified)) + kind
    }

    fun readUri(c: Context, uri: Uri): String? = try {
        c.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) { null }

    /** "Saved Tue Nov 3 · 5:40 PM" for a backup's JSON, or null when it isn't one of ours. */
    fun describe(json: String): String? = try {
        val o = org.json.JSONObject(json)
        val at = o.optLong("__exported_at", 0L)
        val cams = try { org.json.JSONArray(o.optString("cameras", "[]")).length() } catch (e: Exception) { 0 }
        val shows = try { org.json.JSONArray(o.optString("__watch", "[]")).length() } catch (e: Exception) { 0 }
        val favs = try { org.json.JSONArray(o.optString("favorites", "[]")).length() } catch (e: Exception) { 0 }
        (if (at > 0) "Saved ${ShowInfo.whenText(at)}" else "Backup") +
            " · $cams camera(s), $favs favorite(s), $shows show(s)"
    } catch (e: Exception) { null }

    fun pickFile(a: Activity): Boolean = try {
        a.startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_PICK)
        true
    } catch (e: Exception) { false }

    val ADB_ALL_FILES = "adb shell appops set com.moomoo.tvlauncherbase MANAGE_EXTERNAL_STORAGE allow"

    /** Full restore flow: list the backups (newest first), else let the user pick, else explain. */
    fun restoreFlow(a: Activity, done: () -> Unit) {
        val all = list(a)
        if (all.isNotEmpty()) {
            val items = all.mapIndexed { i, e -> (if (i == 0) "★ Newest — " else "") + label(e) } + "Pick a file instead…"
            Ui.choose(a, "Choose a backup (${all.size})", items) { i ->
                if (i == all.size) { if (!pickFile(a)) Ui.toast(a, "This TV has no file picker") ; return@choose }
                val json = read(a, all[i])
                if (json == null) Ui.toast(a, "Couldn't open that backup") else confirmRestore(a, json, done)
            }
            return
        }
        Ui.dialog(a).setTitle("Find your backup")
            .setMessage("${Brand.name} couldn't open $location by itself (Android hides files from a reinstalled or wiped app).\n\n" +
                "• Choose “Pick the file” and open Download → $FOLDER → the newest “${PREFIX}…” file, or\n" +
                "• give ${Brand.name} file access once from a computer, then try again:\n$ADB_ALL_FILES")
            .setPositiveButton("Pick the file") { _, _ ->
                if (!pickFile(a)) Ui.toast(a, "This TV has no file picker — use the adb command")
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    fun confirmRestore(a: Activity, json: String, done: () -> Unit) {
        val d = describe(json)
        if (d == null) { Ui.toast(a, "That file isn't a ${Brand.name} backup"); return }
        Ui.dialog(a).setTitle("Restore your settings?")
            .setMessage("$d\n\nThis replaces the current settings, cameras, button mappings, theme and shows.")
            .setPositiveButton("Restore") { _, _ ->
                if (BasePrefs.importJson(a, json)) {
                    BasePrefs.setSetupDone(a)
                    AppRepository.invalidate()
                    Ui.toast(a, "Restored ✓")
                    done()
                } else Ui.toast(a, "Couldn't read that backup")
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}
