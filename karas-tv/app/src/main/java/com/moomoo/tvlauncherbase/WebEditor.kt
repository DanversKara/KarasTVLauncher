package com.moomoo.tvlauncherbase

import android.content.Context
import android.content.Intent
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * LAN web editor: a tiny HTTP server inside the launcher. Open http://<tv-ip>:8080 on a phone
 * or computer on the same network to edit the header logo, the 12 quick-menu buttons (action +
 * icon), IPTV playlists, the TV guide (EPG) and hand-added channels. Every save goes through
 * the app's normal store layer, then broadcasts [ACTION_REFRESH] so the home screen rebuilds live.
 *
 * Framework APIs only (ServerSocket, hand-rolled HTTP and JSON). No auth by design: "LAN only"
 * comes from the home router (port 8080 is never forwarded). The Settings toggle really stops it.
 */
object WebEditorServer {
    const val PORT = 8080
    const val ACTION_REFRESH = "com.moomoo.tvlauncherbase.WEB_EDIT"

    @Volatile private var running = false
    private var server: ServerSocket? = null

    private const val MAX_BODY = 4 * 1024 * 1024
    private const val MAX_UPLOAD = 32 * 1024 * 1024
    private const val MAX_IMAGE = 2 * 1024 * 1024

    /** Slot id → (default action id, default label). Keep identical to MainActivity.quickDefaults. */
    private val SLOT_DEFS = listOf(
        Triple("tile0", "power", "Power Off"),
        Triple("tile1", "wifi", "Wi-Fi"),
        Triple("tile2", "recents", "Recent Apps"),
        Triple("tile3", "audio", "Audio"),
        Triple("tile4", "sleep", "Sleep Timer"),
        Triple("icon0", "search", "Search"),
        Triple("icon1", "games", "Games"),
        Triple("icon2", "photos", "Photos"),
        Triple("icon3", "home", "Home"),
        Triple("icon4", "apps", "Apps"),
        Triple("icon5", "customize", "Customize"),
        Triple("icon6", "settings", "Settings")
    )

    /** Every built-in action, in the page's dropdown order (= MainActivity.quickBuiltin + hidden). */
    private val ACTIONS = listOf(
        "power" to "Power Off", "wifi" to "Wi-Fi", "recents" to "Recent Apps", "audio" to "Audio",
        "sleep" to "Sleep Timer", "search" to "Search", "games" to "Games", "photos" to "Photos",
        "home" to "Home", "apps" to "Apps", "customize" to "Customize", "settings" to "Settings",
        "guide" to "TV guide", "sys_settings" to "Android settings", "bt" to "Bluetooth", "display" to "Display",
        "apps_settings" to "Manage apps", "storage" to "Storage", "network" to "Network", "about" to "About this TV",
        "datetime" to "Date & time", "accessibility" to "Accessibility", "developer" to "Developer options",
        "screensaver" to "Screensaver", "keyboard" to "Keyboard", "restart" to "Restart launcher",
        "hidden" to "Hidden (off)"
    )

    fun isRunning(): Boolean = running

    /** "http://<ip>:8080" for the first site-local IPv4 address (Wi-Fi or Ethernet), or null. */
    fun address(c: Context): String? {
        try {
            for (ni in NetworkInterface.getNetworkInterfaces() ?: return null) {
                if (!ni.isUp) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) return "http://${a.hostAddress}:$PORT"
                }
            }
        } catch (e: Exception) {}
        return null
    }

    fun applyEnabled(c: Context) { if (BasePrefs.webEditorEnabled(c)) start(c) else stop() }

    @Synchronized fun start(c: Context) {
        if (running) return
        val ss = try {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
        } catch (e: Exception) { return }   // port taken: stay stopped
        server = ss
        running = true
        val app = c.applicationContext
        Thread({
            while (running) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                Thread({ handle(app, s) }, "webeditor-conn").apply { isDaemon = true }.start()
            }
            running = false
        }, "webeditor").apply { isDaemon = true }.start()
    }

    @Synchronized fun stop() {
        running = false
        try { server?.close() } catch (e: Exception) {}
        server = null
    }

    private fun changed(c: Context) {
        try { c.sendBroadcast(Intent(ACTION_REFRESH).setPackage(c.packageName)) } catch (e: Exception) {}
    }

    // ------------------------------------------------------------ HTTP
    /** One header line, read byte by byte. NEVER a BufferedReader: it would swallow body bytes. */
    private fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            sb.append(b.toChar())
            if (sb.length > 16384) break
        }
        return sb.toString().trimEnd('\r')
    }

    private fun readBody(inp: InputStream, len: Int): ByteArray {
        if (len <= 0) return ByteArray(0)
        val buf = ByteArray(len)
        var n = 0
        while (n < len) {
            val r = inp.read(buf, n, len - n)
            if (r < 0) break
            n += r
        }
        return if (n == len) buf else buf.copyOf(n)
    }

    private fun handle(c: Context, sock: Socket) {
        try {
            sock.soTimeout = 8000
            val inp = BufferedInputStream(sock.getInputStream())
            val reqLine = readLine(inp) ?: return
            val parts = reqLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = if (target.contains('?')) target.substringAfter('?') else ""
            var len = 0L
            while (true) {
                val l = readLine(inp) ?: break
                if (l.isEmpty()) break
                val i = l.indexOf(':')
                if (i > 0 && l.substring(0, i).trim().equals("Content-Length", true))
                    len = l.substring(i + 1).trim().toLongOrNull() ?: 0L
            }
            val upload = method == "POST" && path == "/api/iptv/playlist/upload"
            if (upload) sock.soTimeout = 120_000
            if (len > (if (upload) MAX_UPLOAD else MAX_BODY)) {
                respondJson(sock, """{"ok":false,"error":"File too large"}"""); return
            }
            val body = readBody(inp, len.toInt())
            route(c, sock, method, path, query, body)
        } catch (e: Exception) {
            // one bad request never takes down the server or the launcher
        } finally {
            try { sock.close() } catch (e: Exception) {}
        }
    }

    private fun route(c: Context, sock: Socket, method: String, path: String, query: String, body: ByteArray) {
        val text by lazy { String(body, Charsets.UTF_8) }
        when {
            method == "GET" && path == "/" -> respond(sock, 200, "text/html; charset=utf-8", PAGE.toByteArray(Charsets.UTF_8))
            method == "GET" && path == "/api/state" -> respondJson(sock, stateJson(c))
            method == "GET" && path == "/logo" -> serveFile(sock, BasePrefs.logoPath(c))
            method == "GET" && path.startsWith("/icon/") -> serveFile(sock, BasePrefs.quickIconFile(c, path.removePrefix("/icon/")))
            method == "POST" && path == "/api/quick" -> { applyQuick(c, text); respondJson(sock, """{"ok":true}""") }
            method == "POST" && path == "/api/icon" -> {
                val slot = q(query, "slot")
                if (slot == null || SLOT_DEFS.none { it.first == slot }) { respondJson(sock, """{"ok":false,"error":"unknown button"}"""); return }
                val p = saveImage(c, "quick_icons", slot, body)
                if (p == null) respondJson(sock, """{"ok":false,"error":"not a PNG or JPG"}""")
                else { BasePrefs.setQuickIconFile(c, slot, p); changed(c); respondJson(sock, """{"ok":true}""") }
            }
            method == "POST" && path == "/api/icon/delete" -> {
                val slot = q(query, "slot")
                if (slot != null && SLOT_DEFS.any { it.first == slot }) {
                    deleteImage(c, "quick_icons", slot); BasePrefs.setQuickIconFile(c, slot, null); changed(c)
                }
                respondJson(sock, """{"ok":true}""")
            }
            method == "POST" && path == "/api/logo" -> {
                val p = saveImage(c, "logo", "logo", body)
                if (p == null) respondJson(sock, """{"ok":false,"error":"not a PNG or JPG"}""")
                else { BasePrefs.setLogoPath(c, p); changed(c); respondJson(sock, """{"ok":true}""") }
            }
            method == "POST" && path == "/api/logo/delete" -> {
                deleteImage(c, "logo", "logo"); BasePrefs.setLogoPath(c, null); changed(c)
                respondJson(sock, """{"ok":true}""")
            }
            method == "POST" && path == "/api/logo/size" -> {
                jsonInt(text, "size")?.let { BasePrefs.setLogoSizeDp(c, it); changed(c) }
                respondJson(sock, """{"ok":true}""")
            }
            method == "POST" && path == "/api/iptv" -> { applyIptv(c, text); respondJson(sock, """{"ok":true}""") }
            method == "POST" && path == "/api/iptv/playlist" -> {
                applyPlaylist(c, text)
                respondJson(sock, """{"ok":true,"importing":${IptvPlaylistImport.importing}}""")
            }
            method == "POST" && path == "/api/iptv/epg" -> {
                applyEpg(c, text)
                respondJson(sock, """{"ok":true,"fetching":${IptvEpgFetch.fetching}}""")
            }
            method == "POST" && path == "/api/iptv/playlist/upload" -> {
                val raw = q(query, "file")?.let { try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it } } ?: ""
                val name = raw.substringAfterLast('/').substringAfterLast('\\').trim().take(120).ifEmpty { "upload.m3u" }
                val (ok, count, err) = IptvPlaylistUpload.upload(c, name, body)
                if (ok) { changed(c); respondJson(sock, """{"ok":true,"count":$count}""") }
                else respondJson(sock, """{"ok":false,"error":"${esc(err ?: "Upload failed")}"}""")
            }
            else -> respond(sock, 404, "text/plain", "not found".toByteArray())
        }
    }

    private fun q(query: String, key: String): String? =
        query.split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    private fun serveFile(sock: Socket, path: String?) {
        val f = path?.let { File(it) }
        if (f == null || !f.isFile) { respond(sock, 404, "text/plain", "no image".toByteArray()); return }
        respond(sock, 200, if (f.name.endsWith(".jpg", true)) "image/jpeg" else "image/png", f.readBytes())
    }

    private fun respondJson(sock: Socket, json: String) =
        respond(sock, 200, "application/json", json.toByteArray(Charsets.UTF_8))

    private fun respond(sock: Socket, code: Int, type: String, bytes: ByteArray) {
        val reason = when (code) { 200 -> "OK"; 404 -> "Not Found"; else -> "Error" }
        val out = sock.getOutputStream()
        out.write(("HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    // ------------------------------------------------------------ JSON (flat bodies only)
    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    private fun jsonStr(body: String, key: String): String? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(body) ?: return null
        return m.groupValues[1].replace(Regex("""\\(u[0-9a-fA-F]{4}|.)""")) { r ->
            val x = r.groupValues[1]
            when {
                x == "\"" -> "\""; x == "\\" -> "\\"; x == "n" -> "\n"; x == "r" -> "\r"; x == "t" -> "\t"; x == "/" -> "/"
                x.length == 5 && x[0] == 'u' -> x.substring(1).toInt(16).toChar().toString()
                else -> x
            }
        }
    }

    private fun jsonInt(body: String, key: String): Int? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()

    // ------------------------------------------------------------ state
    private fun stateJson(c: Context): String {
        val sb = StringBuilder("{")
        // slots
        sb.append("\"slots\":[")
        SLOT_DEFS.forEachIndexed { i, (slot, defId, defLabel) ->
            val action = BasePrefs.quickAction(c, slot) ?: defId
            val label = ACTIONS.firstOrNull { it.first == action }?.second
                ?: if (action.startsWith("app:")) AppRepository.label(c, action.removePrefix("app:")) else action
            if (i > 0) sb.append(',')
            sb.append("{\"slot\":\"").append(esc(slot)).append("\",\"defLabel\":\"").append(esc(defLabel))
                .append("\",\"action\":\"").append(esc(action)).append("\",\"actionLabel\":\"").append(esc(label))
                .append("\",\"hasIcon\":").append(BasePrefs.quickIconFile(c, slot) != null).append('}')
        }
        sb.append("],\"actions\":[")
        ACTIONS.forEachIndexed { i, (id, label) ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(esc(id)).append("\",\"label\":\"").append(esc(label)).append("\"}")
        }
        sb.append("],\"apps\":[")
        AppRepository.visible(c).forEachIndexed { i, a ->
            if (i > 0) sb.append(',')
            sb.append("{\"pkg\":\"").append(esc(a.packageName)).append("\",\"label\":\"").append(esc(a.label)).append("\"}")
        }
        sb.append("],\"channels\":[")
        BasePrefs.iptvChannels(c).forEachIndexed { i, ch ->
            if (i > 0) sb.append(',')
            sb.append("{\"name\":\"").append(esc(ch.name)).append("\",\"url\":\"").append(esc(ch.url)).append("\"}")
        }
        sb.append("],\"logo\":{\"present\":").append(BasePrefs.logoPath(c) != null)
            .append(",\"size\":").append(BasePrefs.logoSizeDp(c)).append('}')
        val lib = IptvPlaylistStore.loadLibrary(c)
        sb.append(",\"playlists\":[")
        lib.entries.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":\"").append(esc(e.id)).append("\",\"name\":\"").append(esc(e.name))
                .append("\",\"url\":\"").append(esc(e.url)).append("\",\"kind\":\"").append(esc(e.kind))
                .append("\",\"count\":").append(e.count).append(",\"updated\":").append(e.fetchedAt)
                .append(",\"active\":").append(e.id == lib.activeId)
                .append(",\"epgUrl\":\"").append(esc(e.epgUrl)).append("\",\"epgManual\":").append(e.epgManual).append('}')
        }
        sb.append("],\"importing\":").append(IptvPlaylistImport.importing)
        // epg of the active playlist
        val act = IptvPlaylistStore.active(lib)
        val f = act?.let { File(c.filesDir, IptvEpgStore.fileName(it.id)) }
        val d = if (act != null && f != null && f.exists()) IptvEpgStore.load(c, act.id) else null
        if (act == null || d == null) {
            sb.append(",\"epg\":{\"present\":false,\"fetching\":").append(IptvEpgFetch.fetching).append('}')
        } else {
            sb.append(",\"epg\":{\"present\":true,\"fetching\":").append(IptvEpgFetch.fetching)
                .append(",\"url\":\"").append(esc(d.url)).append("\",\"manual\":").append(d.manual)
                .append(",\"channels\":").append(d.channels.size).append(",\"programmes\":").append(d.programmeCount)
                .append(",\"updated\":").append(d.fetchedAt).append(",\"playlist\":\"").append(esc(act.name)).append("\"}")
        }
        sb.append('}')
        return sb.toString()
    }

    // ------------------------------------------------------------ writes
    private fun applyQuick(c: Context, body: String) {
        val slot = jsonStr(body, "slot") ?: return
        val action = jsonStr(body, "action") ?: return
        if (SLOT_DEFS.none { it.first == slot }) return
        if (action.isEmpty()) BasePrefs.setQuickAction(c, slot, null)
        else if (ACTIONS.any { it.first == action } ||
            (action.startsWith("app:") && AppRepository.find(c, action.removePrefix("app:")) != null))
            BasePrefs.setQuickAction(c, slot, action)
        else return
        changed(c)
    }

    private fun applyIptv(c: Context, body: String) {
        val list = BasePrefs.iptvChannels(c)
        val name = jsonStr(body, "name")?.trim()
        val url = jsonStr(body, "url")?.trim()
        val idx = jsonInt(body, "index")
        when (jsonStr(body, "op")) {
            "add" -> {
                if (name.isNullOrEmpty() || url == null || !url.startsWith("http")) return
                list += IptvChannel(name, url)
            }
            "edit" -> {
                if (idx == null || idx !in list.indices || name.isNullOrEmpty() || url == null || !url.startsWith("http")) return
                list[idx] = list[idx].copy(name = name, url = url)
            }
            "delete" -> {
                if (idx == null || idx !in list.indices) return
                list.removeAt(idx)
            }
            else -> return
        }
        BasePrefs.setIptvChannels(c, list)
        changed(c)
    }

    private fun entryFor(c: Context, id: String?): IptvPlaylistStore.Entry? {
        val lib = IptvPlaylistStore.loadLibrary(c)
        return lib.entries.firstOrNull { it.id == id } ?: IptvPlaylistStore.active(lib)
    }

    private fun applyPlaylist(c: Context, body: String) {
        val id = jsonStr(body, "id")
        when (jsonStr(body, "op")) {
            "import" -> {
                val url = jsonStr(body, "url")?.trim() ?: return
                if (!url.startsWith("http")) return
                IptvPlaylistImport.import(c, url) { _, _, _ -> changed(c) }
            }
            "refresh" -> {
                val e = entryFor(c, id) ?: return
                if (e.kind != "url" || !e.url.startsWith("http")) return
                IptvPlaylistImport.refresh(c, e) { _, _, _ -> changed(c) }
            }
            "remove" -> {
                val e = entryFor(c, id) ?: return
                IptvPlaylistStore.removeEntry(c, e.id)
                changed(c)
            }
            "active" -> {
                if (id == null) return
                val e = IptvPlaylistStore.loadLibrary(c).entries.firstOrNull { it.id == id } ?: return
                IptvPlaylistStore.setActive(c, id)
                IptvEpgFetch.ensureForPlaylist(c, e)
                changed(c)
            }
        }
    }

    private fun applyEpg(c: Context, body: String) {
        val e = IptvPlaylistStore.active(IptvPlaylistStore.loadLibrary(c)) ?: return
        when (jsonStr(body, "op")) {
            "set" -> {
                val url = jsonStr(body, "url")?.trim() ?: return
                if (!url.startsWith("http")) return
                IptvPlaylistStore.updateEntry(c, e.copy(epgUrl = url, epgManual = true))
                IptvEpgFetch.fetch(c, e.id, url, true) { _, _, _, _ -> changed(c) }
            }
            "refresh" -> {
                val src = e.epgUrl
                if (src.isEmpty() || !src.startsWith("http")) return
                IptvEpgFetch.fetch(c, e.id, src, e.epgManual) { _, _, _, _ -> changed(c) }
            }
            "remove" -> {
                IptvEpgStore.clear(c, e.id)
                IptvPlaylistStore.updateEntry(c, e.copy(epgUrl = "", epgManual = false))
                changed(c)
            }
        }
    }

    // ------------------------------------------------------------ images
    /** Validates by magic bytes (PNG / JPG), ≤ 2 MB; replaces any old <base>.*; returns the absolute path. */
    private fun saveImage(c: Context, dir: String, base: String, body: ByteArray): String? {
        if (body.isEmpty() || body.size > MAX_IMAGE) return null
        val ext = when {
            body.size >= 4 && body[0] == 0x89.toByte() && body[1] == 0x50.toByte() && body[2] == 0x4E.toByte() && body[3] == 0x47.toByte() -> "png"
            body.size >= 2 && body[0] == 0xFF.toByte() && body[1] == 0xD8.toByte() -> "jpg"
            else -> return null
        }
        val d = File(c.filesDir, dir).apply { mkdirs() }
        d.listFiles()?.forEach { if (it.name.startsWith("$base.")) it.delete() }
        val f = File(d, "$base.$ext")
        f.writeBytes(body)
        return f.absolutePath
    }

    private fun deleteImage(c: Context, dir: String, base: String) {
        File(c.filesDir, dir).listFiles()?.forEach { if (it.name.startsWith("$base.")) it.delete() }
    }

    // ------------------------------------------------------------ the page
    private const val PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Kara's TV — Web editor</title>
<style>
body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#101828;color:#e6e9f0;margin:0}
main{max-width:760px;margin:0 auto;padding:24px}
h1{font-size:24px;margin:0 0 4px}
h2{font-size:18px;margin:32px 0 8px;padding-bottom:6px;border-bottom:1px solid #2a3446}
.sub,.help{color:#9aa3b2;font-size:14px}
.help{margin:6px 0 10px}
.row{display:flex;gap:8px;align-items:center;flex-wrap:wrap;padding:8px 0;border-bottom:1px solid #1d2635}
.lab{width:130px;font-weight:bold;flex:none}
.info{color:#9aa3b2;font-size:13px;width:100%}
input,select{background:#1d2635;color:#e6e9f0;border:1px solid #33405a;border-radius:6px;padding:7px 9px;font-size:14px}
input[type=range]{padding:0}
input.url{flex:1;min-width:200px}
button{background:#f97316;color:#fff;border:0;border-radius:6px;padding:8px 12px;font-size:14px;cursor:pointer}
button.ghost{background:#263248}
.thumb{width:40px;height:40px;object-fit:contain;background:#1d2635;border-radius:6px}
#logoPrev{max-height:64px;max-width:320px;background:#1d2635;border-radius:6px;padding:4px}
.grow{flex:1}
</style></head><body><main>
<h1>Kara's TV — Web editor</h1>
<div class="sub">LAN only · changes apply live on the TV, no restart needed.</div>

<h2>Header logo</h2>
<div class="help">The logo replaces the “Kara's TV” title text in the top bar. Removing it brings the text back.</div>
<div class="row"><img id="logoPrev" alt="logo"><input type="file" id="logoFile" accept="image/png,image/jpeg">
<button id="logoUp">Upload</button><button class="ghost" id="logoDel">Remove</button></div>
<div class="row"><span class="lab">Height</span><input type="range" id="logoSize" min="20" max="120" step="2" value="44" class="grow"><span id="logoSizeVal">44 dp</span></div>

<h2>Quick menu buttons</h2>
<div class="help">These are the buttons in the quick menu that opens from the clock / profile pill on the TV. Pick what each one does — a built-in action or any app — and upload a custom PNG or JPG icon per button if you like. “Hidden (off)” removes a button from the menu; it can only be brought back here, because this page always lists all 12 buttons. Everything saves the moment you change it.</div>
<div id="slots"></div>

<h2>IPTV playlist</h2>
<div class="help">Importing adds a playlist to your library — nothing already saved vanishes. The TV guide shows the active playlist. A playlist usually names its own guide in its url-tvg header, and the TV fetches it on import; if it names none, paste a guide URL below. The guide belongs to the active playlist, so switching playlists switches the guide. With no guide, the TV guide just shows the channels.</div>
<div class="row"><input class="url" id="plUrl" placeholder="Playlist URL (M3U)"><button id="plImport">Import playlist</button></div>
<div class="row"><input type="file" id="plFile" accept=".m3u,text/plain"><button id="plUpload">Upload .m3u file</button></div>
<div id="playlists"></div>
<div class="row"><span class="lab">TV guide</span><span id="epgStatus" class="grow"></span>
<button class="ghost" id="epgRefresh">Refresh guide</button><button class="ghost" id="epgDel">Remove guide</button></div>
<div class="row"><input class="url" id="epgUrl" placeholder="Guide URL (XMLTV, .xml or .xml.gz)"><button id="epgSet">Set guide URL</button></div>

<h2>IPTV channels</h2>
<div class="help">Single streams you add by hand; they show under “My channels” in the TV guide. A link ending in .m3u is a playlist, not a channel — add it in the playlist section above, otherwise it sits here as a dead row that never plays.</div>
<div id="channels"></div>
<div class="row"><input id="newName" placeholder="Channel name"><input class="url" id="newUrl" placeholder="Stream URL (http…)"><button id="chAdd">Add channel</button></div>
</main>
<script>
var S=null,plTimer=null,epgTimer=null;
function g(id){return document.getElementById(id);}
function el(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e;}
function load(){return fetch('/api/state').then(function(r){return r.json();}).then(function(j){S=j;render();});}
function post(path,body,isJson){
  var o={method:'POST'};
  if(isJson){o.headers={'Content-Type':'application/json'};o.body=JSON.stringify(body);}else{o.body=body;}
  return fetch(path,o).then(function(r){return r.json().catch(function(){return {ok:false};});});
}
function when(ms){return new Date(ms).toLocaleString();}
function btn(label,cls,f){var b=el('button',cls,label);b.onclick=f;return b;}
function render(){
  var L=S.logo,lp=g('logoPrev');
  if(L.present){lp.src='/logo?'+Date.now();lp.style.display='';}else{lp.removeAttribute('src');lp.style.display='none';}
  g('logoSize').value=L.size;g('logoSizeVal').textContent=L.size+' dp';

  var pl=g('playlists');pl.textContent='';
  if(S.importing)pl.appendChild(el('div','row','Importing…'));
  if(!S.playlists.length&&!S.importing)pl.appendChild(el('div','row','No playlist imported yet.'));
  S.playlists.forEach(function(p){
    var r=el('div','row');
    r.appendChild(el('span','lab grow',p.name+(p.active?'  ✓ Active':'')));
    if(!p.active)r.appendChild(btn('Set active','ghost',function(){post('/api/iptv/playlist',{op:'active',id:p.id},true).then(load);}));
    if(p.kind!='file')r.appendChild(btn('Refresh','ghost',function(){post('/api/iptv/playlist',{op:'refresh',id:p.id},true).then(function(){load();pollPlaylist();});}));
    r.appendChild(btn('Remove','ghost',function(){if(confirm('Remove playlist "'+p.name+'"?'))post('/api/iptv/playlist',{op:'remove',id:p.id},true).then(load);}));
    r.appendChild(el('div','info',p.count+' channels from '+(p.kind=='file'?'file '+p.url:p.url)+(p.updated?' · updated '+when(p.updated):' · not fetched yet')));
    pl.appendChild(r);
  });

  var E=S.epg,st;
  if(E.fetching)st='Fetching guide… the page updates when it lands.';
  else if(E.present)st='Guide for '+E.playlist+': '+E.programmes+' programmes on '+E.channels+' channels from '+E.url+(E.manual?' (set by hand)':'')+' · updated '+when(E.updated);
  else st='No TV guide data.';
  g('epgStatus').textContent=st;

  var sl=g('slots');sl.textContent='';
  S.slots.forEach(function(s){
    var r=el('div','row');
    r.appendChild(el('span','lab',s.defLabel));
    var sel=el('select');
    var g1=el('optgroup');g1.label='Built-in actions';
    S.actions.forEach(function(a){var o=el('option',null,a.label);o.value=a.id;g1.appendChild(o);});
    var g2=el('optgroup');g2.label='Apps';
    S.apps.forEach(function(a){var o=el('option',null,a.label);o.value='app:'+a.pkg;g2.appendChild(o);});
    sel.appendChild(g1);sel.appendChild(g2);sel.value=s.action;
    sel.onchange=function(){post('/api/quick',{slot:s.slot,action:sel.value},true);};
    r.appendChild(sel);
    r.appendChild(btn('Reset','ghost',function(){post('/api/quick',{slot:s.slot,action:''},true).then(load);}));
    var t=el('img','thumb');
    if(s.hasIcon){t.src='/icon/'+s.slot+'?'+Date.now();}else{t.style.display='none';}
    r.appendChild(t);
    var f=el('input');f.type='file';f.accept='image/png,image/jpeg';
    f.onchange=function(){if(!f.files.length)return;post('/api/icon?slot='+s.slot,f.files[0],false).then(function(j){if(j&&j.ok===false)alert(j.error||'Upload failed');load();});};
    r.appendChild(f);
    if(s.hasIcon)r.appendChild(btn('Remove icon','ghost',function(){post('/api/icon/delete?slot='+s.slot,'',false).then(load);}));
    sl.appendChild(r);
  });

  var ch=g('channels');ch.textContent='';
  S.channels.forEach(function(c,i){
    var r=el('div','row');
    var n=el('input');n.value=c.name;
    var u=el('input','url');u.value=c.url;
    function save(){post('/api/iptv',{op:'edit',index:i,name:n.value,url:u.value},true);}
    n.onchange=save;u.onchange=save;
    r.appendChild(n);r.appendChild(u);
    r.appendChild(btn('Delete','ghost',function(){post('/api/iptv',{op:'delete',index:i},true).then(load);}));
    ch.appendChild(r);
  });
}
function pollPlaylist(){
  if(plTimer)return;
  plTimer=setInterval(function(){load().then(function(){if(!S.importing){clearInterval(plTimer);plTimer=null;load();}});},3000);
}
function pollEpg(){
  if(epgTimer)return;
  epgTimer=setInterval(function(){load().then(function(){if(!S.epg.fetching){clearInterval(epgTimer);epgTimer=null;load();}});},3000);
}
g('logoUp').onclick=function(){var f=g('logoFile').files[0];if(!f){alert('Pick a PNG or JPG first');return;}
  post('/api/logo',f,false).then(function(j){if(j&&j.ok===false)alert(j.error||'Upload failed');load();});};
g('logoDel').onclick=function(){post('/api/logo/delete','',false).then(load);};
g('logoSize').oninput=function(){g('logoSizeVal').textContent=this.value+' dp';};
g('logoSize').onchange=function(){post('/api/logo/size',{size:parseInt(this.value,10)},true).then(load);};
g('chAdd').onclick=function(){var n=g('newName').value.trim(),u=g('newUrl').value.trim();
  if(!n||!u){alert('Name and URL are both needed');return;}
  post('/api/iptv',{op:'add',name:n,url:u},true).then(function(){g('newName').value='';g('newUrl').value='';load();});};
g('plImport').onclick=function(){var u=g('plUrl').value.trim();if(!u){alert('Paste a playlist URL first');return;}
  post('/api/iptv/playlist',{op:'import',url:u},true).then(function(){g('plUrl').value='';load();pollPlaylist();});};
g('plUpload').onclick=function(){var f=g('plFile').files[0];if(!f){alert('Pick an .m3u file first');return;}
  post('/api/iptv/playlist/upload?file='+encodeURIComponent(f.name),f,false).then(function(j){
    if(!j.ok)alert(j.error||'Upload failed');g('plFile').value='';load();});};
g('epgSet').onclick=function(){var u=g('epgUrl').value.trim();if(!u){alert('Paste a guide URL first');return;}
  post('/api/iptv/epg',{op:'set',url:u},true).then(function(){g('epgUrl').value='';load();pollEpg();});};
g('epgRefresh').onclick=function(){post('/api/iptv/epg',{op:'refresh'},true).then(function(){load();pollEpg();});};
g('epgDel').onclick=function(){if(confirm('Remove the TV guide data?'))post('/api/iptv/epg',{op:'remove'},true).then(load);};
load();
</script></body></html>"""
}
