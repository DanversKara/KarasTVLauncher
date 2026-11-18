package com.moomoo.tvlauncherbase

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Stored TV guides — one file per playlist: iptv_epg_<playlistId>.json.
 * Programmes are kept compactly as [start, stop, title, isNew] arrays.
 */
object IptvEpgStore {
    data class Data(val url: String, val fetchedAt: Long, val manual: Boolean, val channels: Map<String, EpgChannel>) {
        val programmeCount get() = channels.values.sumOf { it.programmes.size }
    }

    fun fileName(playlistId: String) = "iptv_epg_$playlistId.json"

    fun toJson(d: Data): String {
        val chans = JSONArray()
        for (ch in d.channels.values) {
            val p = JSONArray()
            for (x in ch.programmes) p.put(JSONArray().put(x.startMs).put(x.stopMs).put(x.title).put(x.isNew))
            chans.put(JSONObject().put("id", ch.id).put("name", ch.name).put("p", p))
        }
        return JSONObject().put("url", d.url).put("fetchedAt", d.fetchedAt).put("manual", d.manual)
            .put("channels", chans).toString()
    }

    fun fromJson(s: String): Data {
        val o = JSONObject(s)
        val a = o.optJSONArray("channels") ?: JSONArray()
        val map = HashMap<String, EpgChannel>(a.length() * 2)
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val p = c.optJSONArray("p") ?: JSONArray()
            val list = ArrayList<EpgProgramme>(p.length())
            for (j in 0 until p.length()) {
                val x = p.getJSONArray(j)
                // files written before isNew existed have 3 fields → false
                list += EpgProgramme(x.getLong(0), x.getLong(1), x.optString(2), x.optBoolean(3, false))
            }
            val id = c.optString("id")
            map[id] = EpgChannel(id, c.optString("name", id), list)
        }
        return Data(o.optString("url"), o.optLong("fetchedAt"), o.optBoolean("manual"), map)
    }

    fun load(dir: File, playlistId: String): Data? {
        val f = File(dir, fileName(playlistId))
        if (!f.exists()) return null
        return try { fromJson(f.readText()) } catch (e: Exception) { null }
    }
    fun load(c: Context, playlistId: String) = load(c.filesDir, playlistId)

    fun save(dir: File, playlistId: String, d: Data) {
        val f = File(dir, fileName(playlistId))
        val tmp = File(dir, fileName(playlistId) + ".tmp")
        tmp.writeText(toJson(d))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }
    fun save(c: Context, playlistId: String, d: Data) = save(c.filesDir, playlistId, d)

    fun clear(dir: File, playlistId: String) { File(dir, fileName(playlistId)).delete() }
    fun clear(c: Context, playlistId: String) = clear(c.filesDir, playlistId)

    /** Matchers for the guide screens, one per playlist id (cached by file time). */
    private val cache = HashMap<String, Pair<Long, EpgMatcher>>()
    @Synchronized fun matcher(c: Context, playlistId: String): EpgMatcher? {
        val f = File(c.filesDir, fileName(playlistId))
        if (!f.exists()) { cache.remove(playlistId); return null }
        cache[playlistId]?.let { if (it.first == f.lastModified()) return it.second }
        val d = load(c, playlistId) ?: return null
        val m = EpgMatcher(d.channels)
        cache[playlistId] = f.lastModified() to m
        return m
    }

    fun fmtTime(c: Context, ms: Long): String = android.text.format.DateFormat.getTimeFormat(c).format(Date(ms))
    fun fmtRange(c: Context, p: EpgProgramme) = "${fmtTime(c, p.startMs)}–${fmtTime(c, p.stopMs)}"
    fun fmtDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))
}

/** Downloads a playlist's guide (XMLTV, plain or .gz) in the background. */
object IptvEpgFetch {
    @Volatile var fetching = false
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private const val MAX_BYTES = 32 * 1024 * 1024

    /** The guide URL a playlist names in its header: url-tvg / x-tvg-url (first one if several). */
    fun extractUrlTvg(m3uText: String): String? {
        val head = m3uText.removePrefix("\uFEFF").lineSequence().take(20)
            .firstOrNull { it.trim().removePrefix("\uFEFF").startsWith("#EXTM3U", true) } ?: return null
        val m = Regex("""(?:url-tvg|x-tvg-url)\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(head) ?: return null
        return m.groupValues[1].split(',', ' ').map { it.trim() }.firstOrNull { it.startsWith("http", true) }
    }

    /** done(ok, programmes, channels, error) on the main thread. */
    fun fetch(c: Context, playlistId: String, url: String, manual: Boolean, done: (Boolean, Int, Int, String?) -> Unit) {
        val app = c.applicationContext
        fetching = true
        Thread {
            val r = try {
                val res = IptvPlaylistImport.openStream(url, MAX_BYTES) { inp ->
                    EpgParser.parse(java.io.InputStreamReader(inp, Charsets.UTF_8), System.currentTimeMillis())
                }
                if (res.channels.isEmpty()) throw java.io.IOException("No guide data found at that address")
                IptvEpgStore.save(app, playlistId, IptvEpgStore.Data(url, System.currentTimeMillis(), manual, res.channels))
                Triple(res.programmeCount, res.channels.size, null as String?)
            } catch (e: Exception) { Triple(-1, 0, ShowInfo.describeError(e)) }
            fetching = false
            main.post { done(r.first >= 0, r.first.coerceAtLeast(0), r.second, r.third) }
        }.start()
    }

    /** After an import: a playlist that names its own guide fetches it by itself (unless one was set by hand). */
    fun autoAfterImport(c: Context, playlistId: String, m3uText: String) {
        val lib = IptvPlaylistStore.loadLibrary(c.filesDir)
        val e = lib.entries.firstOrNull { it.id == playlistId } ?: return
        if (e.epgManual) {
            if (IptvEpgStore.load(c, e.id) == null && e.epgUrl.startsWith("http", true)) fetch(c, e.id, e.epgUrl, true, notify(c))
            return
        }
        val url = extractUrlTvg(m3uText) ?: return
        IptvPlaylistStore.updateEntry(c.filesDir, e.copy(epgUrl = url, epgManual = false))
        fetch(c, e.id, url, false, notify(c))
    }

    /** When a playlist becomes active: fetch its guide if it names one and none is stored yet. */
    fun ensureForPlaylist(c: Context, entry: IptvPlaylistStore.Entry?) {
        val e = entry ?: return
        if (fetching || !e.epgUrl.startsWith("http", true)) return
        if (IptvEpgStore.load(c, e.id) != null) return
        fetch(c, e.id, e.epgUrl, e.epgManual, notify(c))
    }

    /** Tells open screens (home, guides, web editor) that something changed. */
    private fun notify(c: Context): (Boolean, Int, Int, String?) -> Unit = { _, _, _, _ ->
        try { c.applicationContext.sendBroadcast(android.content.Intent(WebEditorServer.ACTION_REFRESH).setPackage(c.packageName)) } catch (e: Exception) {}
    }
}
