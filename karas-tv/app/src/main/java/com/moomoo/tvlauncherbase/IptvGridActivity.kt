package com.moomoo.tvlauncherbase

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cable-style grid guide: 30-minute columns from now, channel rows down the left, programme
 * cells sized by duration, a red line at now. Today through +24 h only (what the stored guide holds).
 *
 * D-pad: up/down = channels, left/right = through time. OK on a programme that's on now plays
 * the channel; OK on a later one shows its details. Long-press OK = favorite.
 * The grid is a ListView — only visible rows exist, so huge playlists stay fast.
 * The remote drives a "selection" (row + time) instead of view focus, which keeps it predictable.
 */
class IptvGridActivity : Activity() {

    private sealed class Item {
        class Header(val text: String) : Item()
        class Chan(val ch: GridChannel, val number: Int, val fav: Boolean) : Item()
    }

    private val SLOT = 30 * 60_000L
    private val h = Handler(Looper.getMainLooper())
    private lateinit var listView: ListView
    private lateinit var title: TextView
    private lateinit var dateText: TextView
    private lateinit var timeRow: LinearLayout
    private lateinit var nowLine: View
    private lateinit var playlistBtn: TextView
    private lateinit var info: TextView

    private var items = listOf<Item>()
    private val progCache = HashMap<Int, List<EpgProgramme>>()
    private var matchers: Map<String, EpgMatcher> = emptyMap()
    private var view = IptvGridData.ALL
    private var sel = 0                   // selected row (always a Chan item)
    private var selAt = 0L                // selected time point
    private var winStart = 0L
    private var cols = 3
    private var colW = 0
    private var leftW = 0
    private var rowH = 0
    private var resumed = false
    private var longPressed = false
    private lateinit var rootView: View

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (resumed) reload(keepPlace = true) }
    }
    private val minuteTick = object : Runnable {
        override fun run() { placeNowLine(); h.postDelayed(this, 30_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LauncherTheme.load(this)
        BasePrefs.setIptvGuideView(this, "grid")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        leftW = dp(240); rowH = dp(64)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(LauncherTheme.background)
            setPadding(dp(32), dp(20), dp(32), dp(12))
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title = Ui.text(this, "TV guide", 24f, LauncherTheme.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        playlistBtn = Ui.button(this, "Playlist: All") { cycleView() }
        fun add(v: View) = bar.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        add(playlistBtn)
        add(Ui.button(this, "☰ List view") { BasePrefs.setIptvGuideView(this, "list"); IptvGuideActivity.open(this); finish() })
        add(Ui.button(this, "↻ Refresh") { IptvChannels.refreshPlaylist(this) { reload(true) } })
        add(Ui.button(this, "⋯") { IptvChannels.manage(this) })
        root.addView(bar)

        info = Ui.text(this, "", 14f, LauncherTheme.softText).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(8), 0, dp(6)) }
        root.addView(info)

        // time header
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        dateText = Ui.text(this, "", 15f, LauncherTheme.text, true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, 0, 0) }
        head.addView(dateText, LinearLayout.LayoutParams(leftW, dp(34)))
        timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(timeRow, LinearLayout.LayoutParams(0, dp(34), 1f))
        root.addView(head)

        val gridFrame = FrameLayout(this)
        listView = ListView(this).apply {
            divider = null; dividerHeight = 0
            itemsCanFocus = false; isFocusable = false; isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(v: AbsListView?, s: Int) {}
                override fun onScroll(v: AbsListView?, first: Int, visible: Int, total: Int) {}
            })
        }
        gridFrame.addView(listView, FrameLayout.LayoutParams(-1, -1))
        nowLine = View(this).apply { setBackgroundColor(Color.parseColor("#E53935")) }
        gridFrame.addView(nowLine, FrameLayout.LayoutParams(dp(2), -1))
        root.addView(gridFrame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        rootView = root
        root.isFocusable = true; root.isFocusableInTouchMode = true; root.requestFocus()

        root.post {
            val avail = gridFrame.width - leftW
            cols = (avail / dp(190)).coerceIn(2, 6)
            colW = avail / cols
            reload(keepPlace = false)
        }
        val f = IntentFilter(WebEditorServer.ACTION_REFRESH)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(refreshReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(refreshReceiver, f)

        val act = IptvPlaylistStore.active(IptvPlaylistStore.loadLibrary(this))
        if (act != null && act.kind == "url" && act.fetchedAt == 0L && !IptvPlaylistImport.importing)
            IptvChannels.refreshPlaylist(this) { reload(true) }
        IptvEpgFetch.ensureForPlaylist(this, act)
    }

    override fun onResume() { super.onResume(); resumed = true; h.post(minuteTick); if (colW > 0) reload(true) }
    override fun onPause() { super.onPause(); resumed = false; h.removeCallbacks(minuteTick) }
    override fun onDestroy() { try { unregisterReceiver(refreshReceiver) } catch (e: Exception) {}; h.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun floorNow() = System.currentTimeMillis() / SLOT * SLOT
    private val span get() = cols * SLOT
    private val maxStart get() = floorNow() + EpgParser.WINDOW_MS - span

    // ------------------------------------------------------------ data
    private fun views(): List<Pair<String, String>> {
        val lib = IptvPlaylistStore.loadLibrary(this)
        val act = IptvPlaylistStore.active(lib)
        val out = ArrayList<Pair<String, String>>()
        out += IptvGridData.ALL to "All"
        act?.let { out += it.id to it.name }
        for (e in lib.entries) if (e.id != act?.id) out += e.id to e.name
        return out
    }

    private fun cycleView() {
        val v = views()
        val i = v.indexOfFirst { it.first == view }
        view = v[(i + 1).mod(v.size)].first
        sel = 0
        reload(keepPlace = false)
    }

    private fun reload(keepPlace: Boolean) {
        if (colW <= 0) return
        val vs = views()
        if (vs.none { it.first == view }) view = IptvGridData.ALL
        val viewName = vs.first { it.first == view }.second
        playlistBtn.text = "Playlist: $viewName"
        val chans = IptvGridData.channels(this, view)
        matchers = IptvGridData.matchers(this)
        progCache.clear()
        val favKeys = BasePrefs.iptvFavorites(this).toSet()
        val list = ArrayList<Item>()
        val favs = chans.filter { IptvGridData.key(it.name) in favKeys }
        if (favs.isNotEmpty()) {
            list += Item.Header("♥ Favorites")
            favs.forEach { list += Item.Chan(it, chans.indexOf(it) + 1, true) }
            list += Item.Header("All channels")
        }
        chans.forEachIndexed { i, ch -> list += Item.Chan(ch, i + 1, IptvGridData.key(ch.name) in favKeys) }
        val oldKey = (items.getOrNull(sel) as? Item.Chan)?.ch?.let { IptvGridData.key(it.name) }
        items = list
        if (!keepPlace || winStart == 0L) { winStart = floorNow(); selAt = System.currentTimeMillis() }
        winStart = winStart.coerceIn(floorNow(), maxStart.coerceAtLeast(floorNow()))
        selAt = selAt.coerceAtLeast(System.currentTimeMillis())
        sel = if (keepPlace && oldKey != null) items.indexOfFirst { it is Item.Chan && IptvGridData.key(it.ch.name) == oldKey }.takeIf { it >= 0 } ?: firstChan(0, 1)
              else firstChan(0, 1)
        title.text = "TV guide · $viewName · ${chans.size} channels"
        val guides = matchers.size
        info.text = when {
            chans.isEmpty() && IptvPlaylistImport.importing -> "Downloading the playlist…"
            chans.isEmpty() -> "No channels yet — ⋯ → ＋ Add playlist, or add channels from the web editor."
            guides == 0 && IptvEpgFetch.fetching -> "Fetching the TV guide…"
            guides == 0 -> "No TV guide data — channels still play. Set a guide URL in the web editor."
            else -> "OK plays what's on now · hold OK to favorite · ◀ ▶ moves through time"
        }
        listView.adapter = adapter
        drawTimeHeader()
        placeNowLine()
        scrollToSel()
    }

    private fun firstChan(from: Int, dir: Int): Int {
        var i = from
        while (i in items.indices) { if (items[i] is Item.Chan) return i; i += dir }
        return items.indexOfFirst { it is Item.Chan }.coerceAtLeast(0)
    }

    private fun progs(pos: Int): List<EpgProgramme> {
        progCache[pos]?.let { return it }
        val it = items.getOrNull(pos) as? Item.Chan ?: return emptyList()
        val p = IptvGridData.programmesFor(it.ch, matchers)
        progCache[pos] = p
        return p
    }

    private fun progAt(pos: Int, t: Long) = progs(pos).firstOrNull { t >= it.startMs && t < it.stopMs }

    // ------------------------------------------------------------ drawing
    private fun drawTimeHeader() {
        timeRow.removeAllViews()
        val f = android.text.format.DateFormat.getTimeFormat(this)
        for (i in 0 until cols) {
            timeRow.addView(Ui.text(this, f.format(Date(winStart + i * SLOT)), 14f, LauncherTheme.softText, true).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, 0, 0)
            }, LinearLayout.LayoutParams(colW, -1))
        }
        dateText.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(winStart))
    }

    private fun placeNowLine() {
        val now = System.currentTimeMillis()
        if (colW <= 0 || now < winStart || now >= winStart + span) { nowLine.visibility = View.GONE; return }
        nowLine.visibility = View.VISIBLE
        nowLine.translationX = leftW + ((now - winStart).toFloat() / SLOT * colW)
    }

    private fun xOf(t: Long) = ((t - winStart).toFloat() / SLOT * colW).toInt()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (items[p] is Item.Header) 0 else 1
        override fun isEnabled(p: Int) = false
        override fun getView(p: Int, convert: View?, parent: ViewGroup?): View {
            val it = items[p]
            if (it is Item.Header) return Ui.text(this@IptvGridActivity, it.text, 16f, LauncherTheme.accent, true).apply {
                setPadding(dp(8), dp(12), 0, dp(6))
            }
            return chanRow(p, it as Item.Chan)
        }
    }

    private fun cellBg(selected: Boolean, onNow: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = Ui.dpf(this@IptvGridActivity, 8f)
        setColor(when { selected -> LauncherTheme.focusFill; onNow -> LauncherTheme.surfaceHi; else -> LauncherTheme.surface })
        if (selected) setStroke(dp(2), LauncherTheme.accent)
    }

    private fun chanRow(pos: Int, item: Item.Chan): View {
        val ctx = this
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = AbsListView.LayoutParams(-1, rowH) }
        val selected = pos == sel
        // channel column
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = Ui.dpf(ctx, 8f)
                setColor(if (selected) LauncherTheme.surfaceHi else LauncherTheme.surface)
            }
            setPadding(dp(8), 0, dp(6), 0)
        }
        left.addView(Ui.text(ctx, item.number.toString(), 13f, LauncherTheme.dimText).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(40), -2))
        val logo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        left.addView(logo, LinearLayout.LayoutParams(dp(44), dp(32)).apply { marginStart = dp(6); marginEnd = dp(6) })
        if (item.ch.logo.startsWith("http")) Img.load(logo, item.ch.logo, dp(88)) else logo.visibility = View.INVISIBLE
        left.addView(Ui.text(ctx, (if (item.fav) "♥ " else "") + item.ch.name, 14f, LauncherTheme.text, true).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(left, LinearLayout.LayoutParams(leftW - dp(4), rowH - dp(4)).apply { marginEnd = dp(4); topMargin = dp(2) })

        // programme cells
        val cells = FrameLayout(ctx)
        val now = System.currentTimeMillis()
        val ps = progs(pos)
        val winEnd = winStart + span
        if (ps.isEmpty()) {
            cells.addView(cell("No guide data", null, selected, true), FrameLayout.LayoutParams(-1, -1))
        } else {
            val selProg = if (selected) progAt(pos, selAt) else null
            for (p in ps) {
                if (p.stopMs <= winStart || p.startMs >= winEnd) continue
                val x0 = xOf(maxOf(p.startMs, winStart)); val x1 = xOf(minOf(p.stopMs, winEnd))
                val onNow = now >= p.startMs && now < p.stopMs
                cells.addView(cell(p.title, p, selProg === p, onNow), FrameLayout.LayoutParams((x1 - x0 - dp(3)).coerceAtLeast(dp(4)), -1).apply {
                    leftMargin = x0
                })
            }
            if (selected && selProg == null) {   // a gap (or past the last programme): show an empty selected slot
                val t0 = selAt / SLOT * SLOT
                cells.addView(View(ctx).apply { background = cellBg(true, false) },
                    FrameLayout.LayoutParams(colW - dp(3), -1).apply { leftMargin = xOf(t0.coerceAtLeast(winStart)) })
            }
        }
        row.addView(cells, LinearLayout.LayoutParams(0, rowH - dp(4), 1f).apply { topMargin = dp(2) })
        return row
    }

    private fun cell(text: String, p: EpgProgramme?, selected: Boolean, onNow: Boolean): View {
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
            background = cellBg(selected, onNow)
            setPadding(dp(10), dp(4), dp(8), dp(4))
            val t = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            if (p?.isNew == true) t.addView(Ui.text(ctx, "NEW", 10f, Color.WHITE, true).apply {
                background = Ui.rounded(ctx, LauncherTheme.accent, 4); setPadding(dp(4), 0, dp(4), 0)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            t.addView(Ui.text(ctx, text, 14f, if (p == null) LauncherTheme.dimText else LauncherTheme.text, p != null).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(t)
            if (p != null) addView(Ui.text(ctx, IptvEpgStore.fmtRange(ctx, p), 12f, LauncherTheme.softText).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }
    }

    // ------------------------------------------------------------ remote
    private fun scrollToSel() {
        val first = listView.firstVisiblePosition
        val last = listView.lastVisiblePosition
        if (sel < first + 1 || last < 0 || sel > last - 1) listView.setSelectionFromTop(sel, (listView.height / 2 - rowH).coerceAtLeast(0))
    }

    private fun redraw() { adapter.notifyDataSetChanged() }

    private fun moveRow(dir: Int) {
        var i = sel + dir
        while (i in items.indices && items[i] !is Item.Chan) i += dir
        if (i !in items.indices) return
        sel = i; redraw(); scrollToSel()
    }

    private fun moveTime(dir: Int) {
        val now = System.currentTimeMillis()
        val cur = progAt(sel, selAt)
        if (dir > 0) {
            val next = cur?.stopMs ?: (selAt / SLOT * SLOT + SLOT)
            if (next >= floorNow() + EpgParser.WINDOW_MS) return
            selAt = next
            while (selAt >= winStart + span && winStart < maxStart) winStart += SLOT
        } else {
            val prev = if (cur != null) cur.startMs - 1 else selAt / SLOT * SLOT - 1
            if (prev < now) { if (selAt <= now + 1) return; selAt = now } else selAt = prev
            progAt(sel, selAt)?.let { selAt = maxOf(it.startMs, now) }
            while (selAt < winStart && winStart > floorNow()) winStart -= SLOT
        }
        drawTimeHeader(); placeNowLine(); redraw()
    }

    private fun activate() {
        val it = items.getOrNull(sel) as? Item.Chan ?: return
        val now = System.currentTimeMillis()
        val p = progAt(sel, selAt)
        if (p == null || (now >= p.startMs && now < p.stopMs) || progs(sel).isEmpty()) {
            if (p == null && selAt > now + SLOT && progs(sel).isNotEmpty()) { Ui.toast(this, "Nothing listed then"); return }
            IptvChannels.play(this, it.ch.name, it.ch.url); return
        }
        Ui.dialog(this).setTitle(p.title)
            .setMessage("${it.ch.name}\n${SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(p.startMs))} · " +
                IptvEpgStore.fmtRange(this, p) + (if (p.isNew) "\nNew episode" else "") + "\n\nIt hasn't started yet.")
            .setPositiveButton("Watch the channel now") { _, _ -> IptvChannels.play(this, it.ch.name, it.ch.url) }
            .setNegativeButton(R.string.ok, null).show()
    }

    private fun toggleFav() {
        val it = items.getOrNull(sel) as? Item.Chan ?: return
        val on = BasePrefs.toggleIptvFavorite(this, IptvGridData.key(it.ch.name))
        Ui.toast(this, if (on) "♥ ${it.ch.name} added to favorites" else "${it.ch.name} removed from favorites")
        reload(keepPlace = true)
    }

    /** The grid "has focus" when no top-bar button does. */
    private fun gridHasFocus() = currentFocus == null || currentFocus === rootView

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!gridHasFocus()) {
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { rootView.requestFocus(); redraw(); return true }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                var i = sel - 1
                while (i >= 0 && items[i] !is Item.Chan) i--
                if (i < 0) playlistBtn.requestFocus() else moveRow(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> { moveRow(1); return true }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { repeat(8) { moveRow(1) }; return true }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { repeat(8) { moveRow(-1) }; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { moveTime(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { moveTime(-1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.repeatCount == 0) { longPressed = false; event.startTracking() }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) { longPressed = true; toggleFav(); return true }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && gridHasFocus()) {
            if (!longPressed && !event.isCanceled) activate()
            longPressed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

}
