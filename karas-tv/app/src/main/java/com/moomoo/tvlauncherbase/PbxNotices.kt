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

    data class Notice(val type: String, val title: String, val who: String, val number: String,
                      val to: String, val line: String, val text: String)

    // ------------------------------------------------------------ the loop

    /** Starts asking, if a PBX is set up and notices are switched on. Safe to call again. */
    fun start(c: Context) {
        val app = c.applicationContext
        if (running || !BasePrefs.pbxEnabled(app) || BasePrefs.pbxUrl(app).isBlank()) return
        running = true
        // The first answer only tells us where "now" is: a launcher coming back after hours
        // should not pour out everything it missed.
        since = -1L
        tick(app)
    }

    fun stop() {
        running = false
        main.post { hide() }
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
        since = o.optLong("latest_id", maxOf(since, 0))
        if (first) return   // only learn where we are; show nothing from before we started
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
            queue.addAll(wanted)
            if (shown == null) showNext(app)
        }
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

    /** Shows one notice now (used by the Test button too). */
    fun preview(c: Context, n: Notice) {
        main.post {
            queue.addFirst(n)
            if (shown == null) showNext(c.applicationContext)
        }
    }

    private fun showNext(app: Context) {
        val n = queue.removeFirstOrNull() ?: return
        val (ctx, type) = host(app) ?: return
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
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
            return
        }
        shown = panel
        shownWm = wm
        main.postDelayed({
            hide()
            if (queue.isNotEmpty()) showNext(app)
        }, BasePrefs.pbxBannerSeconds(app) * 1000L)
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
        val kinds = listOf("call" to "calls", "sms" to "SMS", "chat" to "chat", "voicemail" to "voicemail")
            .filter { BasePrefs.pbxShows(c, it.first) }.joinToString(", ") { it.second }
        return "On · ${if (kinds.isBlank()) "nothing chosen" else kinds} · every ${BasePrefs.pbxPollSeconds(c)}s"
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
