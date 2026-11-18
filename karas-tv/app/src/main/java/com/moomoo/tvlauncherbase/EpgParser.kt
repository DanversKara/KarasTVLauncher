package com.moomoo.tvlauncherbase

import java.io.Reader
import java.util.Calendar
import java.util.TimeZone

/** One programme in the TV guide. isNew comes only from an XMLTV <new/> element. */
data class EpgProgramme(val startMs: Long, val stopMs: Long, val title: String, val isNew: Boolean = false)
data class EpgChannel(val id: String, val name: String, val programmes: List<EpgProgramme>)

/**
 * Streaming XMLTV parser. Pure Kotlin, no Android calls (JVM-testable), constant memory
 * apart from what it keeps: only programmes from now through now + [WINDOW_MS].
 */
object EpgParser {
    const val WINDOW_MS = 24 * 3600_000L

    class Result(val channels: Map<String, EpgChannel>) {
        val programmeCount get() = channels.values.sumOf { it.programmes.size }
    }

    fun parse(input: Reader, nowMs: Long, windowMs: Long = WINDOW_MS): Result {
        val names = HashMap<String, String>()
        val progs = HashMap<String, ArrayList<EpgProgramme>>()
        val r = if (input is java.io.BufferedReader) input else java.io.BufferedReader(input, 64 * 1024)
        val end = nowMs + windowMs

        var chanId: String? = null          // inside <channel>
        var progChan: String? = null        // inside <programme>
        var progStart = 0L; var progStop = 0L; var progTitle: String? = null; var progNew = false
        var capture: String? = null         // "display-name" | "title"
        val text = StringBuilder()

        fun flushProgramme() {
            val ch = progChan ?: return
            val t = progTitle?.trim().orEmpty()
            if (t.isNotEmpty() && progStart > 0 && progStop > progStart && progStop > nowMs && progStart < end)
                progs.getOrPut(ch) { ArrayList() } += EpgProgramme(progStart, progStop, t, progNew)
        }

        val tag = StringBuilder()
        while (true) {
            val c = r.read()
            if (c < 0) break
            if (c != '<'.code) { if (capture != null) text.append(c.toChar()); continue }
            // read one tag
            tag.setLength(0)
            var q = 0
            var cdata = false
            while (true) {
                val d = r.read(); if (d < 0) break
                val ch = d.toChar()
                tag.append(ch)
                if (tag.length == 8 && tag.startsWith("![CDATA[")) { cdata = true }
                if (cdata) { if (tag.endsWith("]]>")) break else continue }
                if (tag.startsWith("!--")) { if (tag.length >= 5 && tag.endsWith("-->")) break else continue }
                if (q != 0) { if (d == q) q = 0; continue }
                if (ch == '"' || ch == '\'') { q = d; continue }
                if (ch == '>') break
            }
            if (cdata) { if (capture != null) text.append(tag, 8, (tag.length - 3).coerceAtLeast(8)); continue }
            val s = tag.toString()
            if (s.startsWith("!") || s.startsWith("?")) continue
            val closing = s.startsWith("/")
            val body = s.removePrefix("/").removeSuffix(">").let { if (it.endsWith("/")) it.dropLast(1) else it }
            val selfClose = s.trimEnd('>').endsWith("/")
            val name = body.substringBefore(' ').substringBefore('\t').substringBefore('\n').trim().lowercase()
            if (!closing) when (name) {
                "channel" -> if (progChan == null) chanId = attr(body, "id")
                "programme" -> {
                    progChan = attr(body, "channel"); progStart = parseTime(attr(body, "start")); progStop = parseTime(attr(body, "stop"))
                    progTitle = null; progNew = false
                    if (selfClose) { flushProgramme(); progChan = null }
                }
                "display-name" -> if (chanId != null && !names.containsKey(chanId!!) && !selfClose) { capture = name; text.setLength(0) }
                "title" -> if (progChan != null && progTitle == null && !selfClose) { capture = name; text.setLength(0) }
                "new" -> if (progChan != null) progNew = true
            } else when (name) {
                "channel" -> chanId = null
                "programme" -> { flushProgramme(); progChan = null }
                "display-name", "title" -> if (capture == name) {
                    val v = unescape(text.toString()).trim()
                    if (name == "title") progTitle = v
                    else chanId?.let { if (v.isNotEmpty()) names[it] = v }
                    capture = null; text.setLength(0)
                }
            }
        }
        val out = HashMap<String, EpgChannel>()
        for (id in names.keys + progs.keys) {
            val p = progs[id]?.sortedBy { it.startMs }?.distinctBy { it.startMs } ?: emptyList()
            out[id] = EpgChannel(id, names[id] ?: id, p)
        }
        return Result(out)
    }

    private fun attr(tag: String, key: String): String? =
        Regex("""(?:^|\s)""" + Regex.escape(key) + """\s*=\s*("([^"]*)"|'([^']*)')""").find(tag)?.let {
            unescape(it.groupValues[2].ifEmpty { it.groupValues[3] })
        }

    fun unescape(s: String): String {
        if (!s.contains('&')) return s
        return Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);""").replace(s) { m ->
            val v = m.groupValues[1]
            when {
                v == "amp" -> "&"; v == "lt" -> "<"; v == "gt" -> ">"; v == "quot" -> "\""; v == "apos" -> "'"
                v.startsWith("#x") -> v.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                else -> v.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            }
        }
    }

    /** XMLTV time "yyyyMMddHHmmss +ZZZZ" (seconds / zone optional; no zone = UTC) → epoch ms, 0 if unreadable. */
    fun parseTime(raw: String?): Long {
        val s = raw?.trim() ?: return 0
        val digits = s.takeWhile { it.isDigit() }
        if (digits.length < 12) return 0
        val rest = s.substring(digits.length).trim()
        var off = 0
        val z = Regex("""^([+-])(\d{2}):?(\d{2})""").find(rest)
        if (z != null) {
            off = (z.groupValues[2].toInt() * 60 + z.groupValues[3].toInt()) * 60_000
            if (z.groupValues[1] == "-") off = -off
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt() - 1, digits.substring(6, 8).toInt(),
            digits.substring(8, 10).toInt(), digits.substring(10, 12).toInt(),
            if (digits.length >= 14) digits.substring(12, 14).toInt() else 0)
        return cal.timeInMillis - off
    }

    private val DECOR = Regex(
        """\b(hd|fhd|uhd|sd|4k|8k|hevc|h\.?26[45]|x26[45]|720p?|1080[pi]?|2160p?|480p?|576p?|50fps|60fps|backup|raw|vip|multi)\b""")
    private val PREFIX = Regex("""^\s*(\|[a-z]{2,4}\||[a-z]{2,4}\s*[:|]\s|\[[a-z]{2,4}]\s*)""")

    /**
     * The shared channel key: lowercase, without quality / region decorations.
     * "Sailor Moon (720p)", "US: Sailor Moon HD", "|UK| Sailor Moon" → "sailor moon".
     * Used for guide matching, favorites and the grid's "All" dedupe.
     */
    fun normalizeName(name: String): String {
        var s = name.lowercase()
        s = s.replace(Regex("""[(\[{][^)\]}]*[)\]}]"""), " ")
        repeat(2) { s = PREFIX.replace(s, " ") }
        s = s.replace('&', ' ').replace('+', ' ')
        s = DECOR.replace(s, " ")
        s = s.replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim().replace(Regex("""\s+"""), " ")
        return s.ifEmpty { name.trim().lowercase() }
    }
}

/** Finds a channel's programmes: by tvg-id first, then by normalized channel name. */
class EpgMatcher(channels: Map<String, EpgChannel>) {
    private val byId = HashMap<String, EpgChannel>()
    private val byName = HashMap<String, EpgChannel>()

    init {
        for ((id, ch) in channels) {
            byId[id.lowercase()] = ch
            val k = EpgParser.normalizeName(ch.name)
            val prev = byName[k]
            if (prev == null || prev.programmes.isEmpty()) byName[k] = ch
            val ki = EpgParser.normalizeName(id.substringBeforeLast('.'))
            if (!byName.containsKey(ki)) byName[ki] = ch
        }
    }

    fun channel(id: String?, name: String): EpgChannel? {
        if (!id.isNullOrBlank()) byId[id.lowercase()]?.let { if (it.programmes.isNotEmpty()) return it }
        return byName[EpgParser.normalizeName(name)]
    }

    fun programmes(id: String?, name: String): List<EpgProgramme> = channel(id, name)?.programmes ?: emptyList()

    fun nowNext(id: String?, name: String, nowMs: Long): Pair<EpgProgramme?, EpgProgramme?> {
        val p = programmes(id, name)
        val i = p.indexOfFirst { it.stopMs > nowMs }
        if (i < 0) return null to null
        val now = p[i].takeIf { it.startMs <= nowMs }
        val next = if (now != null) p.getOrNull(i + 1) else p[i]
        return now to next
    }
}
