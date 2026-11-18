package com.moomoo.tvlauncherbase

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView

/**
 * Fullscreen IPTV player: a VideoView on the built-in MediaPlayer (HLS .m3u8 plays natively,
 * no ExoPlayer). Extras "name" and "url". Centre / play-pause toggles; any key shows the
 * Play/Pause button for a few seconds; Back exits. The picture always shows whole (letterboxed).
 */
class IptvActivity : Activity() {
    private lateinit var video: VideoView
    private lateinit var loading: TextView
    private lateinit var playBtn: TextView
    private val h = Handler(Looper.getMainLooper())
    private val hideBtn = Runnable { playBtn.animate().alpha(0f).setDuration(300).start() }
    private var name = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LauncherTheme.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        name = intent.getStringExtra("name") ?: "Channel"
        val url = intent.getStringExtra("url")
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // A VideoView sized WRAP inside a centred frame keeps the video's aspect ratio (letterboxed).
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        loading = Ui.text(this, "Loading $name…", 22f, Color.WHITE).apply { gravity = Gravity.CENTER }
        root.addView(loading, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        playBtn = Ui.text(this, "❚❚  Pause", 20f, Color.WHITE, true).apply {
            background = Ui.rounded(this@IptvActivity, 0xAA000000.toInt(), 28)
            setPadding(Ui.dp(context, 26), Ui.dp(context, 12), Ui.dp(context, 26), Ui.dp(context, 12))
            alpha = 0f
        }
        root.addView(playBtn, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(this@IptvActivity, 48)
        })
        setContentView(root)

        if (url.isNullOrBlank() || !url.startsWith("http", true)) { fail(); return }
        video.setOnPreparedListener { mp ->
            loading.animate().alpha(0f).setDuration(250).start()
            mp.isLooping = false
            video.start(); updateBtn()
        }
        video.setOnErrorListener { _, _, _ -> fail(); true }
        video.setOnCompletionListener { finish() }
        video.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> { loading.text = "Buffering…"; loading.alpha = 1f }
                MediaPlayer.MEDIA_INFO_BUFFERING_END, MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> loading.alpha = 0f
            }
            false
        }
        try { video.setVideoURI(Uri.parse(url)) } catch (e: Exception) { fail() }
    }

    private fun fail() {
        Ui.toast(this, "Couldn't play $name — the stream is offline or in a format this TV can't play")
        finish()
    }

    private fun updateBtn() { playBtn.text = if (video.isPlaying) "❚❚  Pause" else "▶  Play" }

    private fun flashBtn() {
        updateBtn()
        playBtn.animate().cancel(); playBtn.alpha = 1f
        h.removeCallbacks(hideBtn); h.postDelayed(hideBtn, 3000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> return super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (video.isPlaying) video.pause() else video.start()
                flashBtn(); return true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE ->
                return super.onKeyDown(keyCode, event)
        }
        flashBtn()
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() { super.onPause(); if (!isFinishing) video.pause() }
    override fun onResume() { super.onResume(); if (loading.alpha == 0f) video.start() }
    override fun onDestroy() { h.removeCallbacksAndMessages(null); try { video.stopPlayback() } catch (e: Exception) {}; super.onDestroy() }
}

/** Everything about IPTV channels and playlists that the TV-side menus do. */
object IptvChannels {

    fun play(c: Context, ch: IptvChannel) = play(c, ch.name, ch.url)
    fun play(c: Context, name: String, url: String) {
        c.startActivity(Intent(c, IptvActivity::class.java).putExtra("name", name).putExtra("url", url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** The 📺 top-bar button: a saved playlist opens the guide; else play / pick / add a channel. */
    fun playOrPick(c: Context) {
        if (IptvPlaylistStore.loadLibrary(c).entries.isNotEmpty()) { openGuide(c); return }
        val list = BasePrefs.iptvChannels(c)
        when (list.size) {
            0 -> manage(c)
            1 -> play(c, list[0])
            else -> Ui.choose(c, "IPTV channels", list.map { it.name }) { play(c, list[it]) }
        }
    }

    /** The guide last used (grid by default). */
    fun openGuide(c: Context) {
        if (BasePrefs.iptvGuideView(c) == "list") IptvGuideActivity.open(c) else openGrid(c)
    }
    fun openGrid(c: Context) = c.startActivity(Intent(c, IptvGridActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Long-press 📺, or Settings → IPTV channels. */
    fun manage(c: Context) {
        val lib = IptvPlaylistStore.loadLibrary(c)
        val act = IptvPlaylistStore.active(lib)
        val hand = BasePrefs.iptvChannels(c)
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "📺 TV guide" to { openGuide(c) }
        if (lib.entries.size > 1) items += "⇄ Switch playlist (${lib.entries.size} saved)" to { switchPlaylist(c) }
        if (act != null) {
            if (act.kind == "url") items += "↻ Refresh playlist (${act.name} · ${act.count} channels)" to { refreshPlaylist(c) }
            items += "✕ Remove playlist (${act.name})" to {
                Ui.confirm(c, "Remove playlist?", "“${act.name}” and its TV guide will be removed from this TV.") {
                    IptvPlaylistStore.removeEntry(c, act.id); changed(c); Ui.toast(c, "Removed ${act.name}")
                }
            }
        }
        items += "＋ Add playlist (M3U URL)" to { addPlaylist(c) }
        hand.forEachIndexed { i, ch -> items += "▶ ${ch.name}" to { channelMenu(c, i) } }
        items += "＋ Add channel" to { addChannel(c) }
        Ui.choose(c, "IPTV channels", items.map { it.first }) { items[it].second() }
    }

    fun switchPlaylist(c: Context) {
        val lib = IptvPlaylistStore.loadLibrary(c)
        if (lib.entries.isEmpty()) { Ui.toast(c, "No playlists saved yet"); return }
        val activeId = IptvPlaylistStore.active(lib)?.id
        Ui.choose(c, "Switch playlist", lib.entries.map { (if (it.id == activeId) "✓ " else "") + "${it.name} · ${it.count} channels" }) {
            val e = lib.entries[it]
            IptvPlaylistStore.setActive(c, e.id)
            IptvEpgFetch.ensureForPlaylist(c, e)
            changed(c)
            Ui.toast(c, "Now showing ${e.name}")
        }
    }

    fun addPlaylist(c: Context, then: (() -> Unit)? = null) {
        Ui.prompt(c, "Playlist URL (M3U)", "", "http://…") { v ->
            val url = v.trim()
            if (!url.startsWith("http", true)) { Ui.toast(c, "A playlist link starts with http"); return@prompt }
            importUrl(c, url, then)
        }
    }

    private fun importUrl(c: Context, url: String, then: (() -> Unit)?) {
        Ui.toast(c, "Importing playlist…")
        IptvPlaylistImport.import(c, url) { ok, n, err ->
            changed(c)
            if (ok) {
                Ui.toast(c, "Imported $n channels" + (err?.let { " · $it" } ?: ""))
                if (then != null) then() else openGuide(c)
            } else Ui.toast(c, "Import failed: ${err ?: "unknown error"}")
        }
    }

    fun refreshPlaylist(c: Context, then: (() -> Unit)? = null) {
        val e = IptvPlaylistStore.active(IptvPlaylistStore.loadLibrary(c)) ?: run { Ui.toast(c, "No playlist yet"); return }
        if (e.kind != "url") { Ui.toast(c, "That playlist came from a file — re-upload it to refresh"); return }
        Ui.toast(c, "Refreshing ${e.name}…")
        IptvPlaylistImport.refresh(c, e) { ok, n, err ->
            changed(c)
            Ui.toast(c, if (ok) "Updated: $n channels" else "Refresh failed: ${err ?: "unknown error"}")
            then?.invoke()
        }
    }

    private fun channelMenu(c: Context, i: Int) {
        val list = BasePrefs.iptvChannels(c)
        val ch = list.getOrNull(i) ?: return
        Ui.choose(c, ch.name, listOf("Play", "Edit name", "Edit URL", "Delete")) { w ->
            when (w) {
                0 -> play(c, ch)
                1 -> Ui.prompt(c, "Channel name", ch.name) { v ->
                    if (v.isNotBlank()) save(c) { it[i] = ch.copy(name = v.trim()) }
                }
                2 -> Ui.prompt(c, "Stream URL", ch.url) { v ->
                    if (!v.trim().startsWith("http", true)) Ui.toast(c, "A stream link starts with http")
                    else save(c) { it[i] = ch.copy(url = v.trim()) }
                }
                3 -> Ui.confirm(c, "Delete channel?", "“${ch.name}” will be removed.") { save(c) { it.removeAt(i) } }
            }
        }
    }

    fun addChannel(c: Context) {
        Ui.prompt(c, "Channel name", "") { name ->
            if (name.isBlank()) return@prompt
            Ui.prompt(c, "Stream URL", "", "http://…") { v ->
                val url = v.trim()
                if (!url.startsWith("http", true)) { Ui.toast(c, "A stream link starts with http"); return@prompt }
                if (IptvPlaylistStore.isPlaylistUrl(url)) {
                    // A .m3u link is a playlist, not a channel — never save it as a dead row.
                    Ui.confirm(c, "That's a playlist", "That looks like a playlist (.m3u), not a channel. Import it as a playlist instead?") {
                        importUrl(c, url, null)
                    }
                    return@prompt
                }
                save(c) { it += IptvChannel(name.trim(), url) }
                Ui.toast(c, "Added ${name.trim()}")
            }
        }
    }

    private fun save(c: Context, change: (MutableList<IptvChannel>) -> Unit) {
        val l = BasePrefs.iptvChannels(c); change(l); BasePrefs.setIptvChannels(c, l); changed(c)
    }

    /** Same live-refresh signal the web editor uses: home and open guides redraw. */
    fun changed(c: Context) {
        try { c.applicationContext.sendBroadcast(Intent(WebEditorServer.ACTION_REFRESH).setPackage(c.packageName)) } catch (e: Exception) {}
    }

    /** Settings → IPTV channels summary. */
    fun summary(c: Context): String {
        val hand = BasePrefs.iptvChannels(c)
        val lib = IptvPlaylistStore.loadLibrary(c)
        val act = IptvPlaylistStore.active(lib)
        if (hand.isEmpty() && act == null) return "— tap to add"
        val parts = ArrayList<String>()
        if (hand.isNotEmpty()) parts += "${hand.size} channel${if (hand.size == 1) "" else "s"} (${hand[0].name}${if (hand.size > 1) ", …" else ""})"
        if (act != null) parts += "TV guide: ${act.name} (${act.count} channels)" + if (lib.entries.size > 1) " · ${lib.entries.size} playlists" else ""
        return parts.joinToString(" · ")
    }
}
