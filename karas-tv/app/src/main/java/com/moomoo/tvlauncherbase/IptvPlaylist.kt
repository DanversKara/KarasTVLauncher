package com.moomoo.tvlauncherbase

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** One channel from an imported M3U playlist. */
data class PlaylistChannel(val name: String, val url: String, val logo: String, val group: String, val id: String = "")

/**
 * M3U / M3U8-playlist parser. Pure Kotlin, no Android calls (JVM-testable).
 *
 *   #EXTM3U url-tvg="…"
 *   #EXTINF:-1 tvg-id="x" tvg-logo="…" group-title="News",Channel name
 *   http://…/stream.m3u8
 */
object M3uParser {
    private val ATTR = Regex("""([a-z0-9_-]+)="([^"]*)"""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<PlaylistChannel> {
        val out = ArrayList<PlaylistChannel>()
        var name: String? = null
        var logo = ""; var group = ""; var id = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val attrs = HashMap<String, String>()
                var lastAttrEnd = line.indexOf(':').coerceAtLeast(0)
                for (m in ATTR.findAll(line)) {
                    attrs[m.groupValues[1].lowercase()] = m.groupValues[2]
                    lastAttrEnd = m.range.last + 1
                }
                // The name follows the comma after the LAST quoted attribute
                // (names and attribute values can both contain commas).
                val comma = line.indexOf(',', lastAttrEnd)
                name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                logo = attrs["tvg-logo"] ?: ""
                id = attrs["tvg-id"] ?: ""
                group = (attrs["group-title"] ?: "").split(';').map { it.trim() }.firstOrNull { it.isNotBlank() } ?: ""
                continue
            }
            if (line.startsWith("#")) continue          // #EXTM3U, #EXTVLCOPT, #EXTGRP, …
            val n = name ?: continue                       // a URL with no #EXTINF before it
            name = null
            if (!line.startsWith("http", ignoreCase = true) || n.isEmpty()) continue
            out += PlaylistChannel(n, line, logo, group.ifBlank { "Other" }, id)
        }
        return out
    }
}

/**
 * The playlist library (2.9.8): several saved playlists, one of them active.
 *
 * Files in filesDir:
 *   iptv_playlists.json          — library index: activeId + entries
 *   iptv_playlist_<id>.json      — one playlist's channels
 *   iptv_epg_<id>.json           — that playlist's guide (IptvEpgStore; deleted with it)
 *   iptv_playlist.json / iptv_epg.json — pre-2.9.8 single-playlist files (migration only)
 */
object IptvPlaylistStore {
    const val MAX_CHANNELS = 30000
    const val LIBRARY_FILE = "iptv_playlists.json"
    const val LEGACY_PLAYLIST = "iptv_playlist.json"
    const val LEGACY_EPG = "iptv_epg.json"

    /** kind: "url" (url = the playlist URL) or "file" (url = the uploaded file name). fetchedAt 0 = never fetched. */
    data class Entry(val id: String, val name: String, val url: String, val kind: String,
                     val fetchedAt: Long, val count: Int, val epgUrl: String = "", val epgManual: Boolean = false)
    data class Library(val activeId: String, val entries: List<Entry>)
    data class Data(val url: String, val fetchedAt: Long, val channels: List<PlaylistChannel>, val kind: String = "url")

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(8)
    fun channelsFileName(id: String) = "iptv_playlist_$id.json"

    // ------------------------------------------------------------ JSON
    private fun entryToJson(e: Entry) = JSONObject().put("id", e.id).put("name", e.name).put("url", e.url)
        .put("kind", e.kind).put("fetchedAt", e.fetchedAt).put("count", e.count)
        .put("epgUrl", e.epgUrl).put("epgManual", e.epgManual)
    private fun entryFromJson(o: JSONObject) = Entry(o.optString("id"), o.optString("name"), o.optString("url"),
        o.optString("kind", "url"), o.optLong("fetchedAt"), o.optInt("count"),
        o.optString("epgUrl", ""), o.optBoolean("epgManual", false))

    fun dataToJson(d: Data): String {
        val a = JSONArray()
        for (ch in d.channels) a.put(JSONArray().put(ch.name).put(ch.url).put(ch.logo).put(ch.group).put(ch.id))
        return JSONObject().put("url", d.url).put("fetchedAt", d.fetchedAt).put("kind", d.kind).put("channels", a).toString()
    }
    fun dataFromJson(s: String): Data {
        val o = JSONObject(s)
        val a = o.optJSONArray("channels") ?: JSONArray()
        val list = ArrayList<PlaylistChannel>(a.length())
        for (i in 0 until a.length()) {
            val x = a.get(i)
            if (x is JSONArray) list += PlaylistChannel(x.optString(0), x.optString(1), x.optString(2), x.optString(3, "Other"), x.optString(4, ""))
            else if (x is JSONObject) list += PlaylistChannel(x.optString("name"), x.optString("url"), x.optString("logo"),
                x.optString("group", "Other"), x.optString("id", ""))
        }
        return Data(o.optString("url"), o.optLong("fetchedAt"), list, o.optString("kind", "url"))
    }

    // ------------------------------------------------------------ library
    @Synchronized fun loadLibrary(dir: File): Library {
        val f = File(dir, LIBRARY_FILE)
        var lib = if (f.exists()) try {
            val o = JSONObject(f.readText())
            val a = o.optJSONArray("entries") ?: JSONArray()
            Library(o.optString("activeId"), List(a.length()) { entryFromJson(a.getJSONObject(it)) })
        } catch (e: Exception) { Library("", emptyList()) } else migrateLegacy(dir)
        val collapsed = collapseDuplicates(lib, dir)
        if (collapsed != lib) { lib = collapsed; saveLibrary(dir, lib) }
        return lib
    }
    fun loadLibrary(c: Context): Library = migrateHandAddedPlaylists(c, loadLibrary(c.filesDir))

    @Synchronized fun saveLibrary(dir: File, lib: Library) {
        val a = JSONArray(); lib.entries.forEach { a.put(entryToJson(it)) }
        val f = File(dir, LIBRARY_FILE)
        val tmp = File(dir, "$LIBRARY_FILE.tmp")
        tmp.writeText(JSONObject().put("activeId", lib.activeId).put("entries", a).toString())
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    /** First load after 2.9.8: the old single playlist (and its guide) becomes the first entry, still active. */
    private fun migrateLegacy(dir: File): Library {
        val old = File(dir, LEGACY_PLAYLIST)
        if (!old.exists()) return Library("", emptyList())
        return try {
            val d = dataFromJson(old.readText())
            val id = newId()
            saveEntryData(dir, id, d)
            val oldEpg = File(dir, LEGACY_EPG)
            if (oldEpg.exists()) oldEpg.renameTo(File(dir, IptvEpgStore.fileName(id)))
            val lib = Library(id, listOf(Entry(id, displayName(d.url), d.url, d.kind, d.fetchedAt, d.channels.size)))
            saveLibrary(dir, lib)
            old.delete()
            lib
        } catch (e: Exception) { Library("", emptyList()) }
    }

    fun active(lib: Library): Entry? = lib.entries.firstOrNull { it.id == lib.activeId } ?: lib.entries.firstOrNull()
    fun activeData(c: Context): Data? = active(loadLibrary(c))?.let { loadEntryData(c.filesDir, it.id) }

    fun loadEntryData(dir: File, id: String): Data? {
        val f = File(dir, channelsFileName(id))
        if (!f.exists()) return null
        return try { dataFromJson(f.readText()) } catch (e: Exception) { null }
    }
    fun loadEntryData(c: Context, id: String) = loadEntryData(c.filesDir, id)
    fun saveEntryData(dir: File, id: String, d: Data) {
        val f = File(dir, channelsFileName(id))
        val tmp = File(dir, channelsFileName(id) + ".tmp")
        tmp.writeText(dataToJson(d))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
    }

    @Synchronized fun addEntry(dir: File, e: Entry, makeActive: Boolean = true): Library {
        val lib = loadLibrary(dir)
        val n = Library(if (makeActive || lib.activeId.isEmpty()) e.id else lib.activeId, lib.entries + e)
        saveLibrary(dir, n); return n
    }
    @Synchronized fun updateEntry(dir: File, e: Entry) {
        val lib = loadLibrary(dir)
        saveLibrary(dir, lib.copy(entries = lib.entries.map { if (it.id == e.id) e else it }))
    }
    fun updateEntry(c: Context, e: Entry) = updateEntry(c.filesDir, e)

    @Synchronized fun setActive(dir: File, id: String) {
        val lib = loadLibrary(dir)
        if (lib.entries.any { it.id == id }) saveLibrary(dir, lib.copy(activeId = id))
    }
    fun setActive(c: Context, id: String) = setActive(c.filesDir, id)

    /** Deletes the playlist, its channels and its guide; the next saved playlist becomes active. */
    @Synchronized fun removeEntry(dir: File, id: String) {
        val lib = loadLibrary(dir)
        val rest = lib.entries.filter { it.id != id }
        val act = if (lib.activeId == id) rest.firstOrNull()?.id ?: "" else lib.activeId
        saveLibrary(dir, Library(act, rest))
        File(dir, channelsFileName(id)).delete()
        IptvEpgStore.clear(dir, id)
    }
    fun removeEntry(c: Context, id: String) = removeEntry(c.filesDir, id)

    /** Same source = same playlist: compare URLs without trailing slash / case of scheme+host; files by name. */
    fun sourceKey(url: String, kind: String): String {
        val u = url.trim()
        return if (kind == "file") "file:" + u.lowercase()
        else "url:" + Regex("""^(https?://[^/]+)""", RegexOption.IGNORE_CASE).replace(u.trimEnd('/')) { it.value.lowercase() }
    }
    fun findBySource(lib: Library, url: String, kind: String): Entry? {
        val k = sourceKey(url, kind)
        return lib.entries.firstOrNull { sourceKey(it.url, it.kind) == k }
    }

    /** One entry per source; keeps the active one (else the first), deletes the extra files. */
    fun collapseDuplicates(lib: Library, dir: File? = null): Library {
        val keep = LinkedHashMap<String, Entry>()
        for (e in lib.entries) {
            val k = sourceKey(e.url, e.kind)
            val cur = keep[k]
            if (cur == null) keep[k] = e
            else if (e.id == lib.activeId) { dir?.let { File(it, channelsFileName(cur.id)).delete() }; keep[k] = e }
            else dir?.let { File(it, channelsFileName(e.id)).delete() }
        }
        if (keep.size == lib.entries.size) return lib
        val entries = keep.values.toList()
        val act = if (entries.any { it.id == lib.activeId }) lib.activeId else entries.firstOrNull()?.id ?: ""
        return Library(act, entries)
    }

    /**
     * Stores a freshly downloaded / uploaded playlist: refreshes the entry with the same source
     * in place (same id, active status and guide), else adds a new entry. Returns the entry.
     */
    @Synchronized fun upsertEntry(dir: File, source: String, kind: String, channels: List<PlaylistChannel>,
                                  now: Long, makeActive: Boolean): Entry {
        val lib = loadLibrary(dir)
        val old = findBySource(lib, source, kind)
        val e = (old ?: Entry(newId(), displayName(source), source, kind, 0, 0))
            .copy(url = source, fetchedAt = now, count = channels.size)
        saveEntryData(dir, e.id, Data(source, now, channels, kind))
        val entries = if (old == null) lib.entries + e else lib.entries.map { if (it.id == e.id) e else it }
        val act = if (makeActive || lib.activeId.isEmpty()) e.id else lib.activeId
        saveLibrary(dir, Library(act, entries))
        return e
    }

    /** "my_list.m3u" → "my_list"; "<url>/path/tv.m3u?x" → "tv"; else the host. */
    fun displayName(source: String): String {
        val s = source.trim()
        val path = s.substringBefore('?').substringBefore('#')
        val last = path.trimEnd('/').substringAfterLast('/').substringAfterLast('\\')
        val base = last.substringBeforeLast('.').ifBlank { last }
        val generic = base.isBlank() || base.lowercase() in setOf("get", "playlist", "index", "list", "tv", "iptv")
        if (!generic || !s.startsWith("http", true)) return base.ifBlank { "Playlist" }.take(60)
        val host = Regex("""^https?://([^/:?#]+)""", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.removePrefix("www.")
        return (if (base.isBlank()) host else "${host ?: ""} · $base")?.take(60) ?: "Playlist"
    }

    /** A link to a playlist (.m3u) rather than a single stream (.m3u8 is a stream). */
    fun isPlaylistUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        val path = u.substringBefore('?').substringBefore('#')
        return path.endsWith(".m3u") || Regex("""[?&]type=m3u(_plus)?(&|$)""").containsMatchIn(u)
    }

    /** Pure: hand-added channels whose URL is a playlist move into the library (unfetched). */
    fun migrateHandAdded(lib: Library, hand: List<IptvChannel>): Pair<Library, List<IptvChannel>> {
        val moving = hand.filter { isPlaylistUrl(it.url) }
        if (moving.isEmpty()) return lib to hand
        var entries = lib.entries
        for (ch in moving) if (entries.none { sourceKey(it.url, it.kind) == sourceKey(ch.url, "url") })
            entries = entries + Entry(newId(), ch.name.ifBlank { displayName(ch.url) }, ch.url.trim(), "url", 0, 0)
        val act = if (lib.activeId.isEmpty()) entries.firstOrNull()?.id ?: "" else lib.activeId
        return Library(act, entries) to hand.filter { !isPlaylistUrl(it.url) }
    }

    fun migrateHandAddedPlaylists(c: Context, lib: Library): Library {
        val hand = BasePrefs.iptvChannels(c)
        val (nl, rest) = migrateHandAdded(lib, hand)
        if (nl === lib) return lib
        saveLibrary(c.filesDir, nl)
        BasePrefs.setIptvChannels(c, rest)
        return nl
    }
}

/** Downloads + imports playlists in the background. */
object IptvPlaylistImport {
    @Volatile var importing = false
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private const val MAX_BYTES = 32 * 1024 * 1024

    /** Counts bytes coming off the network and stops past the cap. */
    private class Capped(inp: java.io.InputStream, private val max: Long) : java.io.FilterInputStream(inp) {
        var n = 0L
        private fun add(k: Int): Int { if (k > 0) { n += k; if (n > max) throw java.io.IOException("the file is bigger than ${max / 1048576} MB") }; return k }
        override fun read(): Int { val r = super.read(); if (r >= 0) add(1); return r }
        override fun read(b: ByteArray, off: Int, len: Int): Int = add(super.read(b, off, len))
    }

    /**
     * Opens [url] and hands [use] a stream of the (decompressed) body: follows http↔https
     * redirects, undoes transport gzip and .gz files (by their magic bytes), caps the download.
     */
    fun <T> openStream(url: String, maxBytes: Int, use: (java.io.InputStream) -> T): T {
        var u = url.trim()
        repeat(6) {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) KarasTV")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw java.io.IOException("redirect without a location")
                    u = URL(URL(u), loc).toString(); return@repeat
                }
                if (code !in 200..299) throw java.io.IOException("the server answered HTTP $code")
                var inp: java.io.InputStream = Capped(conn.inputStream, maxBytes.toLong())
                if ("gzip".equals(conn.contentEncoding, true)) inp = java.util.zip.GZIPInputStream(inp, 64 * 1024)
                inp = java.io.BufferedInputStream(inp, 64 * 1024)
                inp.mark(4)
                val b0 = inp.read(); val b1 = inp.read()
                inp.reset()
                if (b0 == 0x1f && b1 == 0x8b) inp = java.io.BufferedInputStream(java.util.zip.GZIPInputStream(inp, 64 * 1024), 64 * 1024)
                return inp.use(use)
            } finally { conn.disconnect() }
        }
        throw java.io.IOException("too many redirects")
    }

    /** Downloads a whole (decompressed) file; text past 4× the cap is refused. */
    fun download(url: String, maxBytes: Int = MAX_BYTES): ByteArray = openStream(url, maxBytes) { inp ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = inp.read(buf); if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > maxBytes * 4L) throw java.io.IOException("the playlist is too big")
        }
        out.toByteArray()
    }

    /** Parses + stores; returns (ok, count, error). Runs on the caller's thread. */
    fun store(c: Context, source: String, kind: String, bytes: ByteArray, makeActive: Boolean): Triple<Boolean, Int, String?> {
        val text = String(bytes, Charsets.UTF_8)
        val all = M3uParser.parse(text)
        if (all.isEmpty()) return Triple(false, 0, "No channels found in that playlist")
        val chans = all.take(IptvPlaylistStore.MAX_CHANNELS)
        IptvPlaylistStore.loadLibrary(c)   // runs migrations first
        val e = IptvPlaylistStore.upsertEntry(c.filesDir, source, kind, chans, System.currentTimeMillis(), makeActive)
        IptvEpgFetch.autoAfterImport(c, e.id, text)
        return Triple(true, chans.size, if (all.size > chans.size) "Kept the first ${chans.size} of ${all.size} channels" else null)
    }

    /** Imports a playlist URL as a new (or refreshed) library entry and makes it active. */
    fun import(c: Context, url: String, done: (Boolean, Int, String?) -> Unit) = run(c, url, true, done)

    /** Re-downloads a URL playlist in place (keeps its id, active status and guide). */
    fun refresh(c: Context, entry: IptvPlaylistStore.Entry, done: (Boolean, Int, String?) -> Unit) {
        if (entry.kind != "url" || !entry.url.startsWith("http", true)) {
            main.post { done(false, 0, "That playlist came from a file — re-upload it to refresh") }; return
        }
        run(c, entry.url, false, done)
    }

    private fun run(c: Context, url: String, makeActive: Boolean, done: (Boolean, Int, String?) -> Unit) {
        val app = c.applicationContext
        importing = true
        Thread {
            val r = try { store(app, url.trim(), "url", download(url), makeActive) }
                catch (e: Exception) { Triple(false, 0, ShowInfo.describeError(e)) }
            importing = false
            main.post { done(r.first, r.second, r.third) }
        }.start()
    }
}

/** An .m3u file uploaded from the web editor. */
object IptvPlaylistUpload {
    const val MAX_BYTES = 32 * 1024 * 1024

    fun upload(c: Context, filename: String, bytes: ByteArray): Triple<Boolean, Int, String?> {
        if (bytes.isEmpty()) return Triple(false, 0, "The file is empty")
        if (bytes.size > MAX_BYTES) return Triple(false, 0, "File too large")
        return try { IptvPlaylistImport.store(c.applicationContext, filename, "file", bytes, true) }
        catch (e: Exception) { Triple(false, 0, e.message ?: "Upload failed") }
    }
}
