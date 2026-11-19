package com.moomoo.tvlauncherbase

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * What just happened on the phone system, on the TV.
 *
 * The OwnPBX "TV Launcher notifications" plugin answers one read-only address with the last
 * few events: a call ringing now, a new text or chat message with its first lines, a new
 * voicemail with the first lines of its transcript. This asks every few seconds and shows a
 * small banner in the corner, over whatever is playing — enough to know who is calling
 * without reaching for a phone.
 *
 * The banner never takes the remote: it is the same kind of window the floating cameras use,
 * and it disappears on its own.
 */
object PbxNotices {
    private const val TAG_TITLE = "OwnPBX"
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    @Volatile private var since = 0L
    private var shown: LinearLayout? = null
    private var shownWm: WindowManager? = null
    private val queue = ArrayDeque<Notice>()
    /** What was on screen lately, so the same call or message cannot come back. */
    private val recent = LinkedHashMap<String, Long>()
    private var appRef: Context? = null
    private val dismiss = Runnable {
        hide()
        val app = appRef
        if (app != null && queue.isNotEmpty()) showNext(app)
    }

    data class Notice(val type: String, val title: String, val who: String, val number: String,
                      val to: String, val line: String, val text: String)

    // ------------------------------------------------------------ the loop

    /** Starts asking, if a PBX is set up and notices are switched on. Safe to call again. */
    fun start(c: Context) {
        val app = c.applicationContext
        if (running || !BasePrefs.pbxEnabled(app) || BasePrefs.pbxUrl(app).isBlank()) return
        running = true
        appRef = app
        // The first answer only tells us where "now" is: a launcher coming back after hours
        // should not pour out everything it missed.
        since = -1L
        tick(app)
    }

    fun stop() {
        running = false
        main.post {
            // Without this a banner put up a moment ago keeps its old dismissal, and the next
            // one inherits a timer that has already run: one of them stays on screen for good.
            main.removeCallbacks(dismiss)
            queue.clear()
            hide()
        }
    }

    /** Settings changed: pick them up without waiting for the next poll. */
    fun restart(c: Context) {
        stop()
        main.postDelayed({ start(c) }, 300)
    }

    private fun tick(app: Context) {
        if (!running) return
        pool.execute {
            val wait = try {
                poll(app)
                BasePrefs.pbxPollSeconds(app) * 1000L
            } catch (e: Exception) {
                // Unreachable PBX, asleep TV, bad token: keep quiet and try again later.
                maxOf(BasePrefs.pbxPollSeconds(app), 15) * 1000L
            }
            main.postDelayed({ tick(app) }, wait)
        }
    }

    private fun poll(app: Context) {
        val base = BasePrefs.pbxUrl(app).trimEnd('/')
        val token = BasePrefs.pbxToken(app)
        if (base.isBlank() || token.isBlank()) return
        val first = since < 0
        val url = "$base/api/plugins/launcher/events?since=${maxOf(since, 0)}&limit=10"
        val body = get(url, token)
        val o = JSONObject(body)
        if (!o.optBoolean("ok")) return
        if (first) {
            // Start from where the PBX says "now" is. Reading latest_id instead left the cursor
            // at 0 whenever nothing had happened yet, and the next poll emptied the whole window
            // onto the screen at once.
            since = o.optLong("now_id", o.optLong("latest_id", 0))
            return
        }
        // A ringing call keeps one id while it rings, so the newest id can be older than the
        // cursor: moving back to it would show the same call again and again.
        since = maxOf(since, o.optLong("latest_id", since))
        val arr = o.optJSONArray("events") ?: return
        val wanted = (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val type = e.optString("type")
            if (!BasePrefs.pbxShows(app, type)) return@mapNotNull null
            Notice(
                type = type,
                title = e.optString("title"),
                who = e.optString("who"),
                number = e.optString("number"),
                to = e.optString("to"),
                line = e.optString("line"),
                text = e.optString("text")
            )
        }
        if (wanted.isEmpty()) return
        main.post {
            queue.addAll(wanted.filter { fresh(it) })
            if (shown == null) showNext(app)
        }
    }

    /**
     * True the first time a notice is seen, false for a repeat within a minute.
     *
     * The PBX decides what is new, but a screen that will not clear is the worst way to find out
     * it got that wrong: one caller, one message, one banner, whatever the feed says.
     */
    private fun fresh(n: Notice): Boolean {
        val now = System.currentTimeMillis()
        val key = "${n.type}|${n.number}|${n.to}|${n.text.take(40)}"
        recent.entries.removeAll { now - it.value > 60_000 }
        if (recent.containsKey(key)) return false
        if (recent.size > 40) recent.remove(recent.keys.first())
        recent[key] = now
        return true
    }

    private fun get(url: String, token: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 12_000
            conn.setRequestProperty("X-Launcher-Token", token)
            conn.setRequestProperty("User-Agent", "KarasTVLauncher (Android TV)")
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------ the banner

    /**
     * Shows one notice now, and says out loud what happened.
     *
     * A sample banner that just does not appear is the least useful answer there is: the window
     * either has nowhere to live or Android refused it, and both used to be swallowed. This
     * reports either way, so one tap on the settings screen tells you which.
     */
    fun preview(c: Context, n: Notice) {
        main.post {
            queue.addFirst(n)
            main.removeCallbacks(dismiss)
            hide()
            showNext(c.applicationContext) { problem -> Ui.toast(c, problem) }
        }
    }

    /** True when a banner has somewhere to appear; false after a reinstall until it is allowed again. */
    fun canShow(c: Context): Boolean = host(c.applicationContext) != null

    /** What stopped the last banner from appearing, for the settings screen. */
    @Volatile var lastProblem: String? = null
        private set

    private fun showNext(app: Context, say: ((String) -> Unit)? = null) {
        val n = queue.removeFirstOrNull() ?: return
        val hosted = host(app)
        if (hosted == null) {
            note("Nothing may draw over other apps yet — turn on Home guard in Settings → Accessibility, or allow “Display over other apps”.", say)
            return
        }
        val (ctx, type) = hosted
        val via = if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) "Home guard" else "the overlay permission"
        val wm = ctx.getSystemService(WindowManager::class.java)
        if (wm == null) {
            note("Android gave no window manager for $via.", say)
            return
        }
        val dm = ctx.resources.displayMetrics

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.glass(ctx, 18, 0xF2)
            val pad = Ui.dp(ctx, 16)
            setPadding(pad, Ui.dp(ctx, 12), pad, Ui.dp(ctx, 12))
            Ui.roundClip(this, Ui.dpf(ctx, 18f))
        }
        panel.addView(Ui.text(ctx, icon(n.type) + "  " + n.title, LauncherTheme.BODY_SP * 0.8f, LauncherTheme.dimText))
        panel.addView(Ui.text(ctx, n.who.ifBlank { n.number.ifBlank { "Unknown" } }, LauncherTheme.BODY_SP * 1.15f, LauncherTheme.text, bold = true))
        val second = secondLine(n)
        if (second.isNotBlank()) {
            panel.addView(Ui.text(ctx, second, LauncherTheme.BODY_SP * 0.8f, LauncherTheme.dimText))
        }
        if (n.text.isNotBlank()) {
            panel.addView(Ui.text(ctx, n.text, LauncherTheme.BODY_SP * 0.9f, LauncherTheme.text).apply {
                maxLines = 3
                setPadding(0, Ui.dp(ctx, 6), 0, 0)
            })
        }

        val margin = Ui.dp(ctx, 28)
        val lp = WindowManager.LayoutParams(
            minOf(Ui.dp(ctx, 420), dm.widthPixels - margin * 2),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // Never takes focus: whatever is playing keeps the remote.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = when (BasePrefs.pbxCorner(app)) {
                "tl" -> Gravity.TOP or Gravity.START
                "bl" -> Gravity.BOTTOM or Gravity.START
                "br" -> Gravity.BOTTOM or Gravity.END
                else -> Gravity.TOP or Gravity.END
            }
            x = margin
            y = margin
            title = TAG_TITLE
        }
        try {
            wm.addView(panel, lp)
        } catch (e: Exception) {
            note("Android refused the banner via $via: ${e.javaClass.simpleName} ${e.message}", say)
            return
        }
        lastProblem = null
        say?.invoke("Banner shown via $via for ${maxOf(BasePrefs.pbxBannerSeconds(app), 2)}s.")
        shown = panel
        shownWm = wm
        appRef = app
        // One dismissal at a time: a second banner must not leave an older timer pending, or the
        // wrong window gets taken down and this one never does.
        main.removeCallbacks(dismiss)
        main.postDelayed(dismiss, maxOf(BasePrefs.pbxBannerSeconds(app), 2) * 1000L)
    }

    /** Remembers why a banner did not appear, and tells whoever asked for it. */
    private fun note(problem: String, say: ((String) -> Unit)?) {
        lastProblem = problem
        say?.invoke(problem)
    }

    private fun hide() {
        val v = shown ?: return
        try { shownWm?.removeView(v) } catch (e: Exception) {}
        shown = null
        shownWm = null
    }

    private fun secondLine(n: Notice): String {
        val parts = mutableListOf<String>()
        if (n.number.isNotBlank() && n.number != n.who) parts += n.number
        if (n.to.isNotBlank()) parts += "to ${n.to}"
        if (n.line == "internal") parts += "inside line"
        return parts.joinToString(" · ")
    }

    private fun icon(type: String) = when (type) {
        "call" -> "☎"
        "sms" -> "✉"
        "chat" -> "💬"
        "voicemail" -> "⦿"
        else -> "•"
    }

    /**
     * Where a window can live, best first: the Home guard accessibility service (no extra
     * permission, and it can draw over other apps), then "Display over other apps".
     */
    private fun host(c: Context): Pair<Context, Int>? {
        HomeGuardService.instance?.let { return it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
        if (Settings.canDrawOverlays(c)) {
            return c.applicationContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        return null
    }

    /** One line for the settings screen. */
    fun statusLine(c: Context): String {
        if (!BasePrefs.pbxEnabled(c)) return "Off"
        if (BasePrefs.pbxUrl(c).isBlank()) return "On — no PBX address yet"
        if (BasePrefs.pbxToken(c).isBlank()) return "On — no token yet"
        if (!canShow(c)) return "On — but nothing may draw over other apps yet (see below)"
        lastProblem?.let { return "On — last banner failed: $it" }
        val kinds = listOf("call" to "calls", "sms" to "SMS", "chat" to "chat", "voicemail" to "voicemail")
            .filter { BasePrefs.pbxShows(c, it.first) }.joinToString(", ") { it.second }
        val loop = if (running) "asking every ${BasePrefs.pbxPollSeconds(c)}s" else "not asking yet — reopen the home screen"
        return "On · ${if (kinds.isBlank()) "nothing chosen" else kinds} · $loop"
    }

    /** Asks the PBX once and says what happened, for the Test button. */
    fun test(c: Context, done: (String) -> Unit) {
        val app = c.applicationContext
        pool.execute {
            val msg = try {
                val base = BasePrefs.pbxUrl(app).trimEnd('/')
                if (base.isBlank()) throw java.io.IOException("No PBX address")
                val o = JSONObject(get("$base/api/plugins/launcher/events?limit=5", BasePrefs.pbxToken(app)))
                if (!o.optBoolean("ok")) "The PBX refused: ${o.optString("error", "unknown")}"
                else "Connected. ${o.optJSONArray("events")?.length() ?: 0} recent event(s)."
            } catch (e: Exception) {
                "Could not reach the PBX: ${e.message}"
            }
            main.post { done(msg) }
        }
    }
}
