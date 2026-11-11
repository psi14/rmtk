package com.inkbridge.app.ink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.input.motionprediction.MotionEventPredictor
import com.inkbridge.app.model.EraseSession
import com.inkbridge.app.model.Op
import com.inkbridge.app.model.Page
import com.inkbridge.app.model.Stroke
import com.inkbridge.app.model.StrokeKind
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

enum class Tool { PEN, HIGHLIGHTER, ERASER }

/**
 * A folha de desenho.
 *  - Caneta: desenha (com pressão). Botão lateral ou ponta-borracha = borracha.
 *  - Dedos: 1 dedo arrasta, 2 dedos fazem zoom; toque rápido com 2 dedos = desfazer, com 3 = refazer.
 *  - A palma da mão é ignorada (os dedos não desenham, salvo se ativar "desenhar com o dedo").
 *
 * Para reduzir o atraso: eventos sem agrupamento (requestUnbufferedDispatch), pontos históricos,
 * os traços da caneta vão sendo desenhados diretamente na imagem em cache, e a previsão de
 * movimento desenha um pequeno "rasto" à frente da caneta.
 */
class InkView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Listener {
        fun onStrokeBegin(page: Page, id: String, kind: StrokeKind, color: Int, width: Float)
        fun onStrokePoints(id: String, pts: FloatArray, from: Int, to: Int)
        fun onStrokeEnd(page: Page, stroke: Stroke)
        fun onStrokeCancel(id: String)
        fun onEraseStep(page: Page, step: EraseSession.Step)
        fun onEraseEnd(page: Page, op: Op?)
        fun onHover(x: Float, y: Float, on: Boolean)
        fun onUndoGesture()
        fun onRedoGesture()
        fun onViewChanged() {}
    }

    var listener: Listener? = null
    var tool = Tool.PEN
    var color = Color.BLACK
    var penWidth = 3f
    var highlighterWidth = 22f
    var eraserRadiusPx = 22f
    var partialEraser = true
    var fingerDraws = false

    var page: Page? = null
        set(value) {
            cancelActive()
            field = value
            fitWidth()
        }

    private val painter = Painter()
    private val predictor = MotionEventPredictor.newInstance(this)

    // transformação atual (página -> ecrã)
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    // imagem em cache e a transformação com que foi desenhada
    private var cache: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private var cacheScale = 1f
    private var cacheTx = 0f
    private var cacheTy = 0f
    private var cacheValid = false
    private val cacheMatrix = Matrix()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF555555.toInt()
    }
    private val deskColor = 0xFFE4E4E4.toInt()

    // traço em curso
    private var penPointer = -1
    private var penIsFinger = false
    private var activeId: String? = null
    private var activeKind = StrokeKind.PEN
    private var activeColor = 0
    private var activeWidth = 0f
    private var pts = FloatArray(3 * 512)
    private var n = 0
    private var sent = 0
    private var predicted: MotionEvent? = null

    // borracha em curso
    private var erase: EraseSession? = null
    private var lastEraseX = 0f
    private var lastEraseY = 0f
    private var cursorX = -1f
    private var cursorY = -1f
    private var showCursor = false

    // gestos com os dedos
    private var gestureActive = false
    private var gestureStart = 0L
    private var gestureMaxPointers = 0
    private var gestureMoved = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f
    private var lastPointerCount = 0

    private var lastHoverSent = 0L

    // ------------------------------------------------------------------ vista

    fun fitWidth() {
        val p = page ?: return
        if (width == 0) return
        val margin = 24f
        scale = (width - 2 * margin) / p.w
        // se a página na horizontal couber inteira, mostra-a toda
        val sh = (height - 2 * margin) / p.h
        if (p.w > p.h) scale = minOf(scale, sh)
        tx = (width - p.w * scale) / 2f
        ty = margin
        invalidateCache()
    }

    fun invalidateCache() {
        cacheValid = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        cache?.recycle()
        cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        cacheCanvas = Canvas(cache!!)
        if (oldw == 0) fitWidth() else invalidateCache()
    }

    private fun rebuildCache() {
        val c = cacheCanvas ?: return
        c.drawColor(deskColor)
        val p = page
        if (p != null) {
            painter.drawPageBackground(c, p, scale, tx, ty)
            painter.drawStrokes(c, p, scale, tx, ty)
            // traço de caneta ainda em curso
            if (activeId != null && activeKind == StrokeKind.PEN && n > 0) {
                c.save(); c.clipRect(tx, ty, tx + p.w * scale, ty + p.h * scale)
                painter.drawPenDot(c, activeColor, activeWidth, pts[0], pts[1], pts[2], scale, tx, ty)
                for (i in 1 until n) painter.drawPenSegment(
                    c, activeColor, activeWidth,
                    pts[(i - 1) * 3], pts[(i - 1) * 3 + 1], pts[(i - 1) * 3 + 2],
                    pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2], scale, tx, ty,
                )
                c.restore()
            }
        }
        cacheScale = scale; cacheTx = tx; cacheTy = ty
        cacheValid = true
    }

    override fun onDraw(canvas: Canvas) {
        if (!cacheValid && !gestureActive) rebuildCache()
        val bmp = cache ?: return
        if (cacheScale == scale && cacheTx == tx && cacheTy == ty) {
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } else {
            // a meio de um zoom/arrasto: estica a imagem antiga (rápido); redesenha no fim
            canvas.drawColor(deskColor)
            val k = scale / cacheScale
            cacheMatrix.setScale(k, k)
            cacheMatrix.postTranslate(tx - cacheTx * k, ty - cacheTy * k)
            canvas.drawBitmap(bmp, cacheMatrix, bmpPaint)
        }

        if (activeId != null) {
            if (activeKind == StrokeKind.HIGHLIGHTER) {
                painter.drawHighlighter(canvas, pts, n, activeColor, activeWidth, scale, tx, ty)
            } else {
                drawPrediction(canvas)
            }
        }
        if (showCursor && (erase != null || tool == Tool.ERASER) && cursorX >= 0) {
            canvas.drawCircle(cursorX, cursorY, eraserRadiusPx, cursorPaint)
        }
    }

    private fun drawPrediction(canvas: Canvas) {
        val ev = predicted ?: return
        if (n == 0 || penPointer < 0) return
        val idx = ev.findPointerIndex(penPointer).takeIf { it >= 0 } ?: 0
        var px = pts[(n - 1) * 3]; var py = pts[(n - 1) * 3 + 1]; var pp = pts[(n - 1) * 3 + 2]
        for (h in 0..ev.historySize) {
            val sx = if (h < ev.historySize) ev.getHistoricalX(idx, h) else ev.getX(idx)
            val sy = if (h < ev.historySize) ev.getHistoricalY(idx, h) else ev.getY(idx)
            val x = (sx - tx) / scale; val y = (sy - ty) / scale
            if (hypot(x - px, y - py) > 40f) break // previsão absurda: ignora
            painter.drawPenSegment(canvas, activeColor, activeWidth, px, py, pp, x, y, pp, scale, tx, ty)
            px = x; py = y
        }
    }

    // ------------------------------------------------------------------ toque

    private fun isStylus(tt: Int) = tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (page == null) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val tt = e.getToolType(idx)
                when {
                    isStylus(tt) -> {
                        if (penPointer != -1 && penIsFinger) cancelActive()
                        if (penPointer == -1) {
                            endGesture(false)
                            startPen(e, idx, finger = false)
                        }
                    }
                    penPointer != -1 && penIsFinger -> {
                        // segundo dedo enquanto desenhava com o dedo: passa a ser zoom
                        cancelActive()
                        startGesture(e)
                    }
                    penPointer == -1 && fingerDraws && e.pointerCount == 1 && !gestureActive ->
                        startPen(e, idx, finger = true)
                    penPointer == -1 -> if (!gestureActive) startGesture(e) else resetGestureAnchor(e)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (penPointer != -1) movePen(e) else if (gestureActive) moveGesture(e)
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val idx = e.actionIndex
                if (penPointer != -1 && e.getPointerId(idx) == penPointer) {
                    val canceled = Build.VERSION.SDK_INT >= 33 && penIsFinger &&
                        (e.flags and MotionEvent.FLAG_CANCELED) != 0
                    if (canceled) cancelActive() else endPen(e, idx)
                } else if (gestureActive) {
                    if (e.actionMasked == MotionEvent.ACTION_UP) endGesture(true) else resetGestureAnchor(e, excluding = idx)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelActive()
                endGesture(false)
            }
        }
        return true
    }

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (!isStylus(e.getToolType(0))) return super.onHoverEvent(e)
        val p = page ?: return true
        val x = (e.x - tx) / scale; val y = (e.y - ty) / scale
        cursorX = e.x; cursorY = e.y
        val on = e.actionMasked != MotionEvent.ACTION_HOVER_EXIT
        showCursor = on && (tool == Tool.ERASER || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER)
        if (tool == Tool.ERASER) invalidate()
        val now = System.currentTimeMillis()
        if (!on || now - lastHoverSent >= 16) {
            lastHoverSent = now
            if (x in -20f..p.w + 20f && y in -20f..p.h + 20f) listener?.onHover(x, y, on) else listener?.onHover(x, y, false)
        }
        return true
    }

    // ------------------------------------------------------------------ caneta

    private fun startPen(e: MotionEvent, idx: Int, finger: Boolean) {
        val p = page ?: return
        penPointer = e.getPointerId(idx)
        penIsFinger = finger
        if (!cacheValid || cacheScale != scale || cacheTx != tx || cacheTy != ty) rebuildCache()
        if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e)
        listener?.onHover(0f, 0f, false)

        val eraserTip = e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER ||
            (e.buttonState and (MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_SECONDARY)) != 0
        if (tool == Tool.ERASER || eraserTip) {
            erase = EraseSession(p, partialEraser)
            showCursor = true
            val x = (e.getX(idx) - tx) / scale; val y = (e.getY(idx) - ty) / scale
            lastEraseX = x; lastEraseY = y
            eraseAt(p, x, y, e.getX(idx), e.getY(idx))
            return
        }

        activeId = Stroke.newId()
        activeKind = if (tool == Tool.HIGHLIGHTER) StrokeKind.HIGHLIGHTER else StrokeKind.PEN
        activeColor = color
        activeWidth = if (activeKind == StrokeKind.HIGHLIGHTER) highlighterWidth else penWidth
        n = 0; sent = 0
        listener?.onStrokeBegin(p, activeId!!, activeKind, activeColor, activeWidth)
        predictor.record(e)
        addPoint(e.getX(idx), e.getY(idx), pressureOf(e, idx, -1))
        flushPoints()
    }

    private fun pressureOf(e: MotionEvent, idx: Int, hist: Int): Float {
        if (penIsFinger) return 0.5f
        val p = if (hist < 0) e.getPressure(idx) else e.getHistoricalPressure(idx, hist)
        return p.coerceIn(0f, 1f)
    }

    private fun movePen(e: MotionEvent) {
        val p = page ?: return
        val idx = e.findPointerIndex(penPointer)
        if (idx < 0) return
        if (erase != null) {
            for (h in 0 until e.historySize) eraseAt(p, (e.getHistoricalX(idx, h) - tx) / scale, (e.getHistoricalY(idx, h) - ty) / scale, e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
            eraseAt(p, (e.getX(idx) - tx) / scale, (e.getY(idx) - ty) / scale, e.getX(idx), e.getY(idx))
            return
        }
        if (activeId == null) return
        predictor.record(e)
        for (h in 0 until e.historySize) addPoint(e.getHistoricalX(idx, h), e.getHistoricalY(idx, h), pressureOf(e, idx, h))
        addPoint(e.getX(idx), e.getY(idx), pressureOf(e, idx, -1))
        predicted?.recycle()
        predicted = if (activeKind == StrokeKind.PEN && !penIsFinger) predictor.predict() else null
        flushPoints()
    }

    private fun endPen(e: MotionEvent, idx: Int) {
        val p = page
        val es = erase
        if (es != null && p != null) {
            erase = null
            showCursor = false
            listener?.onEraseEnd(p, es.toOp())
            penPointer = -1
            invalidate()
            return
        }
        if (activeId != null && p != null) {
            addPoint(e.getX(idx), e.getY(idx), if (penIsFinger) 0.5f else n.takeIf { it > 0 }?.let { pts[(it - 1) * 3 + 2] } ?: 0.5f)
            flushPoints()
            val stroke = Stroke(activeId!!, activeKind, activeColor, activeWidth, pts.copyOf(n * 3))
            p.strokes.add(stroke)
            cacheCanvas?.let { c ->
                if (cacheValid && cacheScale == scale && cacheTx == tx && cacheTy == ty) {
                    if (stroke.kind == StrokeKind.HIGHLIGHTER || stroke.count == 1) {
                        c.save(); c.clipRect(tx, ty, tx + p.w * scale, ty + p.h * scale)
                        painter.drawStroke(c, stroke, scale, tx, ty)
                        c.restore()
                    }
                } else cacheValid = false
            }
            listener?.onStrokeEnd(p, stroke)
        }
        resetActive()
        invalidate()
    }

    /** Cancela o traço ou a borracha em curso (ex.: a palma tocou primeiro). */
    fun cancelActive() {
        val id = activeId
        val es = erase
        val p = page
        if (id != null) {
            listener?.onStrokeCancel(id)
            cacheValid = false
        }
        if (es != null && p != null) listener?.onEraseEnd(p, es.toOp())
        erase = null
        showCursor = false
        resetActive()
        invalidate()
    }

    private fun resetActive() {
        activeId = null
        penPointer = -1
        penIsFinger = false
        n = 0; sent = 0
        predicted?.recycle()
        predicted = null
    }

    private fun addPoint(sx: Float, sy: Float, pressure: Float) {
        val x = (sx - tx) / scale
        val y = (sy - ty) / scale
        if (n > 0) {
            val lx = pts[(n - 1) * 3]; val ly = pts[(n - 1) * 3 + 1]
            if (abs(x - lx) < 0.05f && abs(y - ly) < 0.05f) return
        }
        if ((n + 1) * 3 > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[n * 3] = x; pts[n * 3 + 1] = y; pts[n * 3 + 2] = pressure
        n++
        // a caneta é opaca: desenha logo o novo segmento na cache
        if (activeKind == StrokeKind.PEN && cacheValid && cacheScale == scale && cacheTx == tx && cacheTy == ty) {
            val c = cacheCanvas ?: return
            val p = page ?: return
            c.save(); c.clipRect(tx, ty, tx + p.w * scale, ty + p.h * scale)
            if (n == 1) painter.drawPenDot(c, activeColor, activeWidth, x, y, pressure, scale, tx, ty)
            else painter.drawPenSegment(
                c, activeColor, activeWidth,
                pts[(n - 2) * 3], pts[(n - 2) * 3 + 1], pts[(n - 2) * 3 + 2], x, y, pressure,
                scale, tx, ty,
            )
            c.restore()
        }
    }

    private fun flushPoints() {
        val id = activeId ?: return
        if (n > sent) {
            listener?.onStrokePoints(id, pts, sent, n)
            sent = n
        }
        invalidate()
    }

    private fun eraseAt(p: Page, x: Float, y: Float, sx: Float, sy: Float) {
        val es = erase ?: return
        cursorX = sx; cursorY = sy
        val r = eraserRadiusPx / scale
        // preenche os buracos entre pontos afastados
        val d = hypot(x - lastEraseX, y - lastEraseY)
        val steps = (d / (r / 2f)).toInt().coerceAtMost(50)
        var changed = false
        for (k in 1..steps) {
            val t = k / (steps + 1f)
            val st = es.eraseAt(lastEraseX + (x - lastEraseX) * t, lastEraseY + (y - lastEraseY) * t, r)
            if (!st.isEmpty) { listener?.onEraseStep(p, st); changed = true }
        }
        val st = es.eraseAt(x, y, r)
        if (!st.isEmpty) { listener?.onEraseStep(p, st); changed = true }
        lastEraseX = x; lastEraseY = y
        if (changed) cacheValid = false
        invalidate()
    }

    // ------------------------------------------------------------------ gestos

    private fun startGesture(e: MotionEvent) {
        gestureActive = true
        gestureStart = e.eventTime
        gestureMaxPointers = e.pointerCount
        gestureMoved = 0f
        resetGestureAnchor(e)
    }

    private fun resetGestureAnchor(e: MotionEvent, excluding: Int = -1) {
        var sx = 0f; var sy = 0f; var c = 0
        for (i in 0 until e.pointerCount) {
            if (i == excluding) continue
            sx += e.getX(i); sy += e.getY(i); c++
        }
        if (c == 0) return
        lastFocusX = sx / c; lastFocusY = sy / c
        var span = 0f
        for (i in 0 until e.pointerCount) {
            if (i == excluding) continue
            span += hypot(e.getX(i) - lastFocusX, e.getY(i) - lastFocusY)
        }
        lastSpan = span / c
        lastPointerCount = c
        gestureMaxPointers = max(gestureMaxPointers, e.pointerCount)
    }

    private fun moveGesture(e: MotionEvent) {
        if (e.pointerCount != lastPointerCount) { resetGestureAnchor(e); return }
        var sx = 0f; var sy = 0f
        for (i in 0 until e.pointerCount) { sx += e.getX(i); sy += e.getY(i) }
        val fx = sx / e.pointerCount; val fy = sy / e.pointerCount
        var span = 0f
        for (i in 0 until e.pointerCount) span += hypot(e.getX(i) - fx, e.getY(i) - fy)
        span /= e.pointerCount

        if (e.pointerCount >= 2 && lastSpan > 10f) {
            val k = (span / lastSpan)
            val newScale = (scale * k).coerceIn(minScale(), maxScale())
            val kk = newScale / scale
            tx = fx - (fx - tx) * kk
            ty = fy - (fy - ty) * kk
            scale = newScale
        }
        tx += fx - lastFocusX
        ty += fy - lastFocusY
        gestureMoved += hypot(fx - lastFocusX, fy - lastFocusY) + abs(span - lastSpan)
        lastFocusX = fx; lastFocusY = fy; lastSpan = span
        clampPan()
        invalidate()
    }

    private fun endGesture(allowTap: Boolean) {
        if (!gestureActive) return
        gestureActive = false
        if (allowTap && android.os.SystemClock.uptimeMillis() - gestureStart < 280 && gestureMoved < 30f) {
            when (gestureMaxPointers) {
                2 -> listener?.onUndoGesture()
                3 -> listener?.onRedoGesture()
            }
        }
        cacheValid = false
        invalidate()
        listener?.onViewChanged()
    }

    private fun minScale(): Float {
        val p = page ?: return 0.2f
        return minOf(width / p.w, height / p.h) * 0.4f
    }

    private fun maxScale(): Float {
        val p = page ?: return 8f
        return width / p.w * 6f
    }

    private fun clampPan() {
        val p = page ?: return
        val pw = p.w * scale; val ph = p.h * scale
        val keep = 80f
        tx = tx.coerceIn(keep - pw, width - keep)
        ty = ty.coerceIn(keep - ph, height - keep)
    }

    /** Usado ao mudar de página (para continuar no topo). */
    fun scrollToTop() {
        ty = 24f
        invalidateCache()
    }

    fun onPageContentChanged() = invalidateCache()
}
