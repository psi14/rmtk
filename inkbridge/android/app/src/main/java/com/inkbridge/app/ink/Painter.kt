package com.inkbridge.app.ink

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.inkbridge.app.model.Page
import com.inkbridge.app.model.Stroke
import com.inkbridge.app.model.StrokeKind
import com.inkbridge.app.model.Template
import com.inkbridge.app.model.TemplateSpec

/**
 * Desenha páginas e traços num Canvas, com uma transformação simples:
 * ecrã = página * scale + (tx, ty). Usado no ecrã e na exportação para PDF.
 */
class Painter {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint().apply { style = Paint.Style.FILL }
    private val shadow = Paint().apply { style = Paint.Style.FILL; color = 0x22000000 }
    private val path = Path()

    fun highlighterColor(color: Int) =
        Color.argb(TemplateSpec.HIGHLIGHTER_ALPHA, Color.red(color), Color.green(color), Color.blue(color))

    /** Fundo da página (branco + linhas), já recortado à página. */
    fun drawPageBackground(c: Canvas, page: Page, scale: Float, tx: Float, ty: Float, withShadow: Boolean = true) {
        val l = tx; val t = ty; val r = tx + page.w * scale; val b = ty + page.h * scale
        if (withShadow) c.drawRect(l + 3, t + 4, r + 3, b + 4, shadow)
        fill.color = Color.WHITE
        c.drawRect(l, t, r, b, fill)
        c.save()
        c.clipRect(l, t, r, b)
        line.strokeWidth = (TemplateSpec.LINE_WIDTH * scale).coerceAtLeast(1f)
        line.color = TemplateSpec.LINE_COLOR
        when (page.template) {
            Template.BLANK -> {}
            Template.LINED -> {
                var y = TemplateSpec.LINE_TOP
                while (y < page.h) {
                    c.drawLine(l, ty + y * scale, r, ty + y * scale, line); y += TemplateSpec.LINE_SPACING
                }
                line.color = TemplateSpec.MARGIN_COLOR
                val mx = tx + TemplateSpec.MARGIN_X * scale
                c.drawLine(mx, t, mx, b, line)
            }
            Template.GRID -> {
                val s = TemplateSpec.GRID_SPACING
                var x = s
                while (x < page.w) { c.drawLine(tx + x * scale, t, tx + x * scale, b, line); x += s }
                var y = s
                while (y < page.h) { c.drawLine(l, ty + y * scale, r, ty + y * scale, line); y += s }
            }
            Template.DOTS -> {
                dot.color = TemplateSpec.LINE_COLOR
                val s = TemplateSpec.DOT_SPACING
                val rad = (TemplateSpec.DOT_RADIUS * scale).coerceAtLeast(1f)
                var y = s
                while (y < page.h) {
                    var x = s
                    while (x < page.w) { c.drawCircle(tx + x * scale, ty + y * scale, rad, dot); x += s }
                    y += s
                }
            }
        }
        c.restore()
    }

    fun drawStrokes(c: Canvas, page: Page, scale: Float, tx: Float, ty: Float) {
        c.save()
        c.clipRect(tx, ty, tx + page.w * scale, ty + page.h * scale)
        for (s in page.strokes) drawStroke(c, s, scale, tx, ty)
        c.restore()
    }

    fun drawStroke(c: Canvas, s: Stroke, scale: Float, tx: Float, ty: Float) {
        if (s.kind == StrokeKind.HIGHLIGHTER) {
            drawHighlighter(c, s.pts, s.count, s.color, s.width, scale, tx, ty)
            return
        }
        val n = s.count
        if (n == 1) {
            dot.color = s.color
            c.drawCircle(tx + s.x(0) * scale, ty + s.y(0) * scale, s.widthAt(0) * scale / 2, dot)
            return
        }
        for (i in 1 until n) {
            drawPenSegment(
                c, s.color, s.width,
                s.x(i - 1), s.y(i - 1), s.p(i - 1), s.x(i), s.y(i), s.p(i),
                scale, tx, ty,
            )
        }
    }

    fun drawPenSegment(
        c: Canvas, color: Int, width: Float,
        x0: Float, y0: Float, p0: Float, x1: Float, y1: Float, p1: Float,
        scale: Float, tx: Float, ty: Float,
    ) {
        pen.color = color
        pen.strokeWidth = (Stroke.widthFor(StrokeKind.PEN, width, (p0 + p1) / 2f) * scale).coerceAtLeast(0.8f)
        c.drawLine(tx + x0 * scale, ty + y0 * scale, tx + x1 * scale, ty + y1 * scale, pen)
    }

    fun drawPenDot(c: Canvas, color: Int, width: Float, x: Float, y: Float, p: Float, scale: Float, tx: Float, ty: Float) {
        dot.color = color
        c.drawCircle(tx + x * scale, ty + y * scale, Stroke.widthFor(StrokeKind.PEN, width, p) * scale / 2, dot)
    }

    fun drawHighlighter(c: Canvas, pts: FloatArray, count: Int, color: Int, width: Float, scale: Float, tx: Float, ty: Float) {
        if (count == 0) return
        hl.color = highlighterColor(color)
        hl.strokeWidth = width * scale
        path.rewind()
        path.moveTo(tx + pts[0] * scale, ty + pts[1] * scale)
        if (count == 1) path.lineTo(tx + pts[0] * scale + 0.1f, ty + pts[1] * scale)
        for (i in 1 until count) path.lineTo(tx + pts[i * 3] * scale, ty + pts[i * 3 + 1] * scale)
        c.drawPath(path, hl)
    }
}
