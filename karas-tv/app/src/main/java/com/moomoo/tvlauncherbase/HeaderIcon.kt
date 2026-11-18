package com.moomoo.tvlauncherbase

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crisp line icons for the header, drawn in code in the style of Google's
 * Material Symbols (2 px rounded strokes): header buttons plus the quick-menu
 * glyphs (home, photos, games, power, Wi-Fi, recents, audio, sleep, TV, person). They take the theme's text color,
 * so they look right on every theme.
 */
class HeaderIcon(c: Context, val kind: Kind) : View(c) {

    enum class Kind { SEARCH, APPS, CAMERA, PIP, EDIT, SETTINGS, PERSON, HOME, PHOTOS, GAMES, POWER, WIFI, RECENTS, AUDIO, SLEEP, TV }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val r = RectF()

    init { isFocusable = false; isDuplicateParentStateEnabled = true }
    /** Color override (the header picks a color that reads on the wallpaper behind it). */
    var tint: Int? = null
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        p.color = tint ?: LauncherTheme.text
        p.strokeWidth = s * 0.085f
        p.style = Paint.Style.STROKE
        when (kind) {
            Kind.SEARCH -> {
                canvas.drawCircle(s * 0.42f, s * 0.42f, s * 0.25f, p)
                canvas.drawLine(s * 0.61f, s * 0.61f, s * 0.84f, s * 0.84f, p)
            }
            Kind.APPS -> {
                p.style = Paint.Style.FILL
                for (row in 0..2) for (col in 0..2) {
                    val cx = s * (0.22f + col * 0.28f); val cy = s * (0.22f + row * 0.28f)
                    canvas.drawCircle(cx, cy, s * 0.075f, p)
                }
            }
            Kind.CAMERA -> {
                r.set(s * 0.1f, s * 0.3f, s * 0.9f, s * 0.82f)
                canvas.drawRoundRect(r, s * 0.1f, s * 0.1f, p)
                path.reset()
                path.moveTo(s * 0.32f, s * 0.3f); path.lineTo(s * 0.38f, s * 0.18f)
                path.lineTo(s * 0.62f, s * 0.18f); path.lineTo(s * 0.68f, s * 0.3f)
                canvas.drawPath(path, p)
                canvas.drawCircle(s * 0.5f, s * 0.56f, s * 0.15f, p)
            }
            Kind.PIP -> {
                r.set(s * 0.1f, s * 0.2f, s * 0.9f, s * 0.8f)
                canvas.drawRoundRect(r, s * 0.08f, s * 0.08f, p)
                p.style = Paint.Style.FILL
                r.set(s * 0.5f, s * 0.48f, s * 0.78f, s * 0.68f)
                canvas.drawRoundRect(r, s * 0.04f, s * 0.04f, p)
            }
            Kind.EDIT -> {
                path.reset()
                path.moveTo(s * 0.2f, s * 0.8f); path.lineTo(s * 0.24f, s * 0.62f)
                path.lineTo(s * 0.66f, s * 0.2f); path.lineTo(s * 0.8f, s * 0.34f)
                path.lineTo(s * 0.38f, s * 0.76f); path.close()
                canvas.drawPath(path, p)
                canvas.drawLine(s * 0.58f, s * 0.28f, s * 0.72f, s * 0.42f, p)
            }
            Kind.SETTINGS -> {
                val cx = s / 2f; val cy = s / 2f
                path.reset()
                val teeth = 8
                val inner = s * 0.3f; val outer = s * 0.41f
                val half = Math.PI / teeth
                var first = true
                for (i in 0 until teeth) {
                    val a = 2 * Math.PI * i / teeth
                    // each tooth: inner → outer → outer (flat top) → inner
                    for ((ang, rad) in listOf(a - half * 0.95 to inner, a - half * 0.45 to outer,
                            a + half * 0.45 to outer, a + half * 0.95 to inner)) {
                        val x = cx + (cos(ang) * rad).toFloat(); val y = cy + (sin(ang) * rad).toFloat()
                        if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                    }
                }
                path.close()
                canvas.drawPath(path, p)
                canvas.drawCircle(cx, cy, s * 0.13f, p)
            }
            Kind.PERSON -> {
                canvas.drawCircle(s * 0.5f, s * 0.36f, s * 0.16f, p)
                r.set(s * 0.18f, s * 0.6f, s * 0.82f, s * 1.1f)
                canvas.drawArc(r, 180f, 180f, false, p)
            }
            Kind.HOME -> {
                path.reset()
                path.moveTo(s * 0.14f, s * 0.48f); path.lineTo(s * 0.5f, s * 0.16f); path.lineTo(s * 0.86f, s * 0.48f)
                canvas.drawPath(path, p)
                path.reset()
                path.moveTo(s * 0.24f, s * 0.4f); path.lineTo(s * 0.24f, s * 0.84f); path.lineTo(s * 0.76f, s * 0.84f); path.lineTo(s * 0.76f, s * 0.4f)
                canvas.drawPath(path, p)
                r.set(s * 0.42f, s * 0.6f, s * 0.58f, s * 0.84f)
                canvas.drawRect(r, p)
            }
            Kind.PHOTOS -> {
                r.set(s * 0.12f, s * 0.18f, s * 0.88f, s * 0.82f)
                canvas.drawRoundRect(r, s * 0.08f, s * 0.08f, p)
                path.reset()
                path.moveTo(s * 0.2f, s * 0.72f); path.lineTo(s * 0.42f, s * 0.48f); path.lineTo(s * 0.58f, s * 0.64f)
                path.lineTo(s * 0.66f, s * 0.56f); path.lineTo(s * 0.8f, s * 0.72f)
                canvas.drawPath(path, p)
                canvas.drawCircle(s * 0.66f, s * 0.36f, s * 0.06f, p)
            }
            Kind.GAMES -> {
                r.set(s * 0.1f, s * 0.3f, s * 0.9f, s * 0.74f)
                canvas.drawRoundRect(r, s * 0.2f, s * 0.2f, p)
                canvas.drawLine(s * 0.25f, s * 0.52f, s * 0.41f, s * 0.52f, p)
                canvas.drawLine(s * 0.33f, s * 0.44f, s * 0.33f, s * 0.6f, p)
                p.style = Paint.Style.FILL
                canvas.drawCircle(s * 0.62f, s * 0.47f, s * 0.045f, p)
                canvas.drawCircle(s * 0.72f, s * 0.57f, s * 0.045f, p)
            }
            Kind.POWER -> {
                r.set(s * 0.2f, s * 0.22f, s * 0.8f, s * 0.82f)
                canvas.drawArc(r, -60f, 300f, false, p)
                canvas.drawLine(s * 0.5f, s * 0.12f, s * 0.5f, s * 0.48f, p)
            }
            Kind.WIFI -> {
                for ((i, rad) in listOf(0.42f, 0.28f, 0.14f).withIndex()) {
                    r.set(s * (0.5f - rad), s * (0.74f - rad), s * (0.5f + rad), s * (0.74f + rad))
                    if (i < 3) canvas.drawArc(r, 225f, 90f, false, p)
                }
                p.style = Paint.Style.FILL
                canvas.drawCircle(s * 0.5f, s * 0.74f, s * 0.05f, p)
            }
            Kind.RECENTS -> {
                r.set(s * 0.26f, s * 0.26f, s * 0.86f, s * 0.78f)
                canvas.drawRoundRect(r, s * 0.06f, s * 0.06f, p)
                path.reset()
                path.moveTo(s * 0.14f, s * 0.64f); path.lineTo(s * 0.14f, s * 0.2f); path.lineTo(s * 0.7f, s * 0.2f)
                canvas.drawPath(path, p)
            }
            Kind.AUDIO -> {
                path.reset()
                path.moveTo(s * 0.14f, s * 0.4f); path.lineTo(s * 0.3f, s * 0.4f); path.lineTo(s * 0.5f, s * 0.22f)
                path.lineTo(s * 0.5f, s * 0.78f); path.lineTo(s * 0.3f, s * 0.6f); path.lineTo(s * 0.14f, s * 0.6f); path.close()
                canvas.drawPath(path, p)
                r.set(s * 0.46f, s * 0.36f, s * 0.74f, s * 0.64f); canvas.drawArc(r, -50f, 100f, false, p)
                r.set(s * 0.44f, s * 0.22f, s * 0.88f, s * 0.78f); canvas.drawArc(r, -50f, 100f, false, p)
            }
            Kind.SLEEP -> {   // crescent moon
                val outer = Path().apply { addCircle(s * 0.48f, s * 0.52f, s * 0.34f, Path.Direction.CW) }
                val inner = Path().apply { addCircle(s * 0.66f, s * 0.38f, s * 0.28f, Path.Direction.CW) }
                outer.op(inner, Path.Op.DIFFERENCE)
                canvas.drawPath(outer, p)
            }
            Kind.TV -> {
                r.set(s * 0.1f, s * 0.24f, s * 0.9f, s * 0.74f)
                canvas.drawRoundRect(r, s * 0.07f, s * 0.07f, p)
                canvas.drawLine(s * 0.34f, s * 0.86f, s * 0.66f, s * 0.86f, p)
                canvas.drawLine(s * 0.38f, s * 0.12f, s * 0.5f, s * 0.24f, p)
                canvas.drawLine(s * 0.62f, s * 0.12f, s * 0.5f, s * 0.24f, p)
            }
        }
        canvas.restore()
    }
}
