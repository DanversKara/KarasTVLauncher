package com.moomoo.tvlauncherbase

import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** PHONE SYSTEM: banners on the TV when somebody calls, texts, chats or leaves voicemail. */
class PbxSettingsActivity : RowListActivity() {

    override val screenTitle = "Phone system notices"
    override val screenDesc = "Shows who is calling, and new texts, chat messages and voicemail, " +
        "in a corner of the screen over whatever is playing. Set it up on the PBX first: " +
        "Plugins → TV Launcher notifications, which gives you the address and the token."

    private fun onOff(v: Boolean) = if (v) "On" else "Off"

    override fun rows(): List<Row> {
        val c = this
        val out = mutableListOf<Row>()

        out += Row("Status", PbxNotices.statusLine(c)) {}

        if (!canShowBanner()) {
            out += Row("Let banners appear over other apps",
                "Settings → Accessibility → Kara's TV → On. Without it a banner can only show on the home screen.") {
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } catch (e: Exception) {}
            }
            out += Row("…or allow “Display over other apps”",
                "If this page is missing on your TV, run from a computer:\nadb shell appops set $packageName SYSTEM_ALERT_WINDOW allow") {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } catch (e: Exception) { Ui.toast(c, "This TV hides that page — use the adb command shown") }
            }
        }

        out += Row("Connection", header = true)
        out += Row("Show notices", onOff(BasePrefs.pbxEnabled(c))) {
            BasePrefs.setPbxEnabled(c, !BasePrefs.pbxEnabled(c))
            PbxNotices.restart(c)
            refresh()
        }
        out += Row("PBX address", BasePrefs.pbxUrl(c).ifBlank { "Not set — e.g. https://pbx.example.org" }) {
            Ui.prompt(c, "PBX address", BasePrefs.pbxUrl(c), "https://pbx.example.org") { v ->
                BasePrefs.setPbxUrl(c, v)
                PbxNotices.restart(c)
                refresh()
            }
        }
        out += Row("Token", if (BasePrefs.pbxToken(c).isBlank()) "Not set" else "Set (" + BasePrefs.pbxToken(c).take(6) + "…)") {
            Ui.prompt(c, "Token", BasePrefs.pbxToken(c), "from the plugin's page on the PBX") { v ->
                BasePrefs.setPbxToken(c, v)
                PbxNotices.restart(c)
                refresh()
            }
        }
        out += Row("Test the connection", "Asks the PBX once and says what came back") {
            PbxNotices.test(c) { msg -> Ui.toast(c, msg) }
        }
        out += Row("Show a sample banner", "See where it appears and how long it stays") {
            PbxNotices.preview(c, PbxNotices.Notice(
                type = "call", title = "Incoming call", who = "Kara Danvers",
                number = "15555550123", to = "611 Kara Danvers", line = "pstn", text = ""
            ))
        }

        out += Row("What to show", header = true)
        listOf("call" to "Calls", "sms" to "Text messages", "chat" to "Chat messages", "voicemail" to "Voicemail")
            .forEach { (kind, label) ->
                out += Row(label, onOff(BasePrefs.pbxShows(c, kind))) {
                    BasePrefs.setPbxShows(c, kind, !BasePrefs.pbxShows(c, kind))
                    refresh()
                }
            }

        out += Row("Appearance", header = true)
        out += Row("Corner", cornerName(BasePrefs.pbxCorner(c))) {
            val o = listOf("tr" to "Top right", "tl" to "Top left", "br" to "Bottom right", "bl" to "Bottom left")
            Ui.choose(c, "Corner", o.map { it.second }) { BasePrefs.setPbxCorner(c, o[it].first); refresh() }
        }
        out += Row("How long it stays", "${BasePrefs.pbxBannerSeconds(c)} seconds") {
            val o = listOf(6, 10, 12, 20, 30)
            Ui.choose(c, "How long it stays", o.map { "$it seconds" }) { BasePrefs.setPbxBannerSeconds(c, o[it]); refresh() }
        }
        out += Row("How often to check", "Every ${BasePrefs.pbxPollSeconds(c)} seconds · shorter catches a call sooner") {
            val o = listOf(3, 5, 10, 20, 60)
            Ui.choose(c, "How often to check", o.map { "Every $it seconds" }) {
                BasePrefs.setPbxPollSeconds(c, o[it])
                PbxNotices.restart(c)
                refresh()
            }
        }
        return out
    }

    private fun cornerName(v: String) = when (v) {
        "tl" -> "Top left"
        "br" -> "Bottom right"
        "bl" -> "Bottom left"
        else -> "Top right"
    }

    private fun canShowBanner(): Boolean =
        HomeGuardService.instance != null || Settings.canDrawOverlays(this)
}
