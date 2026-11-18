package com.moomoo.tvlauncherbase

import android.content.Context

/** One row of the grid guide. sourceId = the playlist it came from ("" = hand-added). */
data class GridChannel(val name: String, val url: String, val logo: String, val id: String, val sourceId: String)

/** Builds the grid guide's channel lists and resolves their programmes. */
object IptvGridData {
    const val ALL = "__all__"

    fun key(name: String) = EpgParser.normalizeName(name)

    /** Hand-added channels first, then each playlist in order; deduped by normalized name, first wins. */
    fun mergeAll(hand: List<IptvChannel>, playlists: List<Pair<String, List<PlaylistChannel>>>): List<GridChannel> {
        val seen = HashSet<String>()
        val out = ArrayList<GridChannel>()
        for (h in hand) if (seen.add(key(h.name))) out += GridChannel(h.name, h.url, "", "", "")
        for ((pid, chans) in playlists) for (ch in chans)
            if (seen.add(key(ch.name))) out += GridChannel(ch.name, ch.url, ch.logo, ch.id, pid)
        return out
    }

    /** The channels for one view: [ALL], or one playlist id (hand-added channels are in every view, first). */
    fun channels(c: Context, view: String): List<GridChannel> {
        val hand = BasePrefs.iptvChannels(c)
        val lib = IptvPlaylistStore.loadLibrary(c)
        val ids = if (view == ALL) lib.entries.map { it.id } else listOf(view)
        val lists = ids.mapNotNull { id -> IptvPlaylistStore.loadEntryData(c, id)?.let { id to it.channels } }
        return mergeAll(hand, lists)
    }

    /** A channel's programmes: its own playlist's guide first, then any other saved guide. */
    fun programmesFor(ch: GridChannel, matchers: Map<String, EpgMatcher>): List<EpgProgramme> {
        matchers[ch.sourceId]?.programmes(ch.id, ch.name)?.let { if (it.isNotEmpty()) return it }
        for ((pid, m) in matchers) if (pid != ch.sourceId) {
            val p = m.programmes(ch.id, ch.name)
            if (p.isNotEmpty()) return p
        }
        return emptyList()
    }

    fun matchers(c: Context): Map<String, EpgMatcher> {
        val out = LinkedHashMap<String, EpgMatcher>()
        for (e in IptvPlaylistStore.loadLibrary(c).entries) IptvEpgStore.matcher(c, e.id)?.let { out[e.id] = it }
        return out
    }
}
