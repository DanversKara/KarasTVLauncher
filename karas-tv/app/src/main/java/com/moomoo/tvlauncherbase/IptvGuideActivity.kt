package com.moomoo.tvlauncherbase

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * List TV guide: one horizontal row per category. "My channels" (hand-added) first, then
 * "My Playlists", then the active playlist's categories — 48 cards per row plus a "＋ More" tile.
 * Now/Next lines where the guide matches. 🔍 searches every channel by name (first 100).
 * Long-press a card to favorite it.
 */
class IptvGuideActivity : Activity() {

    companion object {
        private const val PAGE = 48
        private const val GROUP_PAGE = 12
        fun open(c: Context) {
            BasePrefs.setIptvGuideView(c, "list")
            c.startActivity(Intent(c, IptvGuideActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private lateinit var list: LinearLayout
    private lateinit var title: TextView
    private var search: String? = null
    private var groupsShown = GROUP_PAGE
    private val shownPerGroup = HashMap<String, Int>()
    private var matcher: EpgMatcher? = null
    private var data: IptvPlaylistStore.Data? = null
    private var active: IptvPlaylistStore.Entry? = null
    private var resumed = false
    private var dirty = false

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (resumed) build() else dirty = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LauncherTheme.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(LauncherTheme.background)
            setPadding(dp(40), dp(24), dp(40), dp(16))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title = Ui.text(this, "TV guide", 26f, LauncherTheme.text, true)
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        fun barBtn(label: String, f: () -> Unit) = Ui.button(this, label, f).also {
            bar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        }
        barBtn("🔍 Search") {
            Ui.prompt(this, "Search channels", search ?: "") { v -> search = v.trim().ifEmpty { null }; build() }
        }
        barBtn("▦ Grid view") { BasePrefs.setIptvGuideView(this, "grid"); IptvChannels.openGrid(this); finish() }
        barBtn("＋ Add playlist") { IptvChannels.addPlaylist(this) { build() } }
        barBtn("↻ Refresh playlist") { IptvChannels.refreshPlaylist(this) { build() } }
        root.addView(bar)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(list) },
            LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        setContentView(root)
        val f = IntentFilter(WebEditorServer.ACTION_REFRESH)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(refreshReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(refreshReceiver, f)

        // A playlist that was never downloaded (moved here from an old channel row) is fetched on first use.
        val act = IptvPlaylistStore.active(IptvPlaylistStore.loadLibrary(this))
        if (act != null && act.kind == "url" && act.fetchedAt == 0L && !IptvPlaylistImport.importing)
            IptvChannels.refreshPlaylist(this) { build() }
        IptvEpgFetch.ensureForPlaylist(this, act)
    }

    override fun onResume() { super.onResume(); resumed = true; build(); dirty = false }
    override fun onPause() { super.onPause(); resumed = false }
    override fun onDestroy() { try { unregisterReceiver(refreshReceiver) } catch (e: Exception) {}; super.onDestroy() }

    private fun dp(v: Int) = Ui.dp(this, v)

    private fun build() {
        val focusTag = currentFocus?.tag as? String
        val lib = IptvPlaylistStore.loadLibrary(this)
        active = IptvPlaylistStore.active(lib)
        data = active?.let { IptvPlaylistStore.loadEntryData(this, it.id) }
        matcher = active?.let { IptvEpgStore.matcher(this, it.id) }
        title.text = "TV guide" + (active?.let { " · ${it.name}" } ?: "")
        list.removeAllViews()
        val hand = BasePrefs.iptvChannels(this)
        val chans = data?.channels ?: emptyList()

        val q = search
        if (q != null) {
            val hits = (hand.map { PlaylistChannel(it.name, it.url, "", "My channels") } + chans)
                .filter { it.name.contains(q, ignoreCase = true) }
            header("Search: “$q” — ${hits.size} found" + if (hits.size > 100) " (first 100 shown)" else "")
            list.addView(Ui.button(this, "✕ Clear search") { search = null; build() },
                LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
            row("search", hits.take(100), 100)
            return
        }

        if (hand.isNotEmpty()) {
            header("My channels")
            row("hand", hand.map { PlaylistChannel(it.name, it.url, "", "My channels") }, hand.size)
        }
        if (lib.entries.isNotEmpty()) {
            header("My Playlists")
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (e in lib.entries) r.addView(playlistCard(e, e.id == active?.id))
            list.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; addView(r) })
        }
        if (active == null && hand.isEmpty()) {
            list.addView(Ui.text(this, "No channels yet.\n\nAdd a playlist (M3U link) with ＋ Add playlist, or add single channels " +
                "from the 📺 button (hold it) or Settings → IPTV channels. You can also use the web editor from your phone.",
                18f, LauncherTheme.softText).apply { setPadding(0, dp(30), 0, 0) })
        }
        if (active != null && chans.isEmpty()) {
            list.addView(Ui.text(this, if (IptvPlaylistImport.importing) "Downloading ${active!!.name}…"
                else "${active!!.name} has no channels stored yet — use ↻ Refresh playlist.", 18f, LauncherTheme.softText)
                .apply { setPadding(0, dp(20), 0, 0) })
        }
        // Favorites, then each category.
        val favKeys = BasePrefs.iptvFavorites(this).toSet()
        val favs = chans.filter { EpgParser.normalizeName(it.name) in favKeys }
        if (favs.isNotEmpty()) { header("♥ Favorites"); row("fav", favs, favs.size) }
        val groups = LinkedHashMap<String, MutableList<PlaylistChannel>>()
        for (ch in chans) groups.getOrPut(ch.group) { ArrayList() } += ch
        var n = 0
        for ((g, l) in groups) {
            if (n++ >= groupsShown) {
                val left = groups.size - groupsShown
                list.addView(Ui.button(this, "Show $left more categor${if (left == 1) "y" else "ies"}") {
                    groupsShown += GROUP_PAGE; build()
                }.apply { tag = "more-groups" }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
                break
            }
            header("$g  ·  ${l.size}")
            row("g:$g", l, shownPerGroup[g] ?: PAGE)
        }
        focusTag?.let { t -> list.findViewWithTag<View>(t)?.requestFocus() }
    }

    private fun header(s: String) {
        list.addView(Ui.text(this, s, 19f, LauncherTheme.text, true).apply { setPadding(dp(4), dp(18), 0, dp(8)) })
    }

    private fun row(key: String, chans: List<PlaylistChannel>, limit: Int) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        for (ch in chans.take(limit)) r.addView(card(ch, "$key|${ch.url}"))
        if (chans.size > limit) {
            val left = chans.size - limit
            r.addView(moreTile("＋ More", "$left left", "$key|more") {
                val g = key.removePrefix("g:")
                shownPerGroup[g] = limit + PAGE; build()
                list.findViewWithTag<View>("$key|${chans[limit].url}")?.requestFocus()
            })
        }
        list.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; addView(r) })
    }

    private fun cardBase(tag: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true; isClickable = true
        background = Ui.focusBg(this@IptvGuideActivity, LauncherTheme.surface, 14)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        this.tag = tag
        layoutParams = LinearLayout.LayoutParams(dp(220), dp(150)).apply { marginEnd = dp(12) }
    }

    private fun card(ch: PlaylistChannel, tag: String): View = cardBase(tag).apply {
        val fav = BasePrefs.isIptvFavorite(this@IptvGuideActivity, EpgParser.normalizeName(ch.name))
        val top = LinearLayout(this@IptvGuideActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this@IptvGuideActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        top.addView(logo, LinearLayout.LayoutParams(dp(56), dp(40)).apply { marginEnd = dp(8) })
        if (ch.logo.startsWith("http")) Img.load(logo, ch.logo, dp(112)) { ok -> if (!ok) logo.visibility = View.GONE }
        else logo.visibility = View.GONE
        top.addView(Ui.text(this@IptvGuideActivity, (if (fav) "♥ " else "") + ch.name, 15f, LauncherTheme.text, true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(top)
        val (now, next) = matcher?.nowNext(ch.id, ch.name, System.currentTimeMillis()) ?: (null to null)
        now?.let {
            addView(Ui.text(this@IptvGuideActivity, "Now: ${it.title} · ${IptvEpgStore.fmtRange(context, it)}", 12.5f, LauncherTheme.softText).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
            })
        }
        next?.let {
            addView(Ui.text(this@IptvGuideActivity, "Next: ${it.title} · ${IptvEpgStore.fmtTime(context, it.startMs)}", 12f, LauncherTheme.dimText).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, 0)
            })
        }
        setOnClickListener { IptvChannels.play(this@IptvGuideActivity, ch.name, ch.url) }
        setOnLongClickListener {
            val on = BasePrefs.toggleIptvFavorite(this@IptvGuideActivity, EpgParser.normalizeName(ch.name))
            Ui.toast(this@IptvGuideActivity, if (on) "♥ ${ch.name} added to favorites" else "${ch.name} removed from favorites")
            build(); true
        }
    }

    private fun moreTile(big: String, small: String, tag: String, onClick: () -> Unit): View = cardBase(tag).apply {
        gravity = Gravity.CENTER
        addView(Ui.text(context, big, 18f, LauncherTheme.text, true).apply { gravity = Gravity.CENTER })
        addView(Ui.text(context, small, 13f, LauncherTheme.softText).apply { gravity = Gravity.CENTER })
        setOnClickListener { onClick() }
    }

    private fun playlistCard(e: IptvPlaylistStore.Entry, isActive: Boolean): View = cardBase("pl|${e.id}").apply {
        addView(Ui.text(context, (if (isActive) "✓ " else "") + e.name, 16f, LauncherTheme.text, true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        addView(Ui.text(context, "${e.count} channels" + if (e.kind == "file") " · file" else "", 13f, LauncherTheme.softText)
            .apply { setPadding(0, dp(6), 0, 0) })
        addView(Ui.text(context, if (isActive) "Active" else "Select to show", 12.5f,
            if (isActive) LauncherTheme.accent else LauncherTheme.dimText, isActive).apply { setPadding(0, dp(4), 0, 0) })
        setOnClickListener {
            if (isActive) return@setOnClickListener
            Ui.dialog(this@IptvGuideActivity).setTitle("Set as active?")
                .setMessage("Show “${e.name}” (${e.count} channels) in the TV guide?")
                .setPositiveButton("Yes") { _, _ ->
                    IptvPlaylistStore.setActive(this@IptvGuideActivity, e.id)
                    IptvEpgFetch.ensureForPlaylist(this@IptvGuideActivity, e)
                    groupsShown = GROUP_PAGE; shownPerGroup.clear()
                    IptvChannels.changed(this@IptvGuideActivity)
                    if (e.kind == "url" && e.fetchedAt == 0L) IptvChannels.refreshPlaylist(this@IptvGuideActivity) { build() }
                    build()
                }.setNegativeButton("No", null).show()
        }
    }
}
