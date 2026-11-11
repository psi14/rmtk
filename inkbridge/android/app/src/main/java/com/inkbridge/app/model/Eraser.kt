package com.inkbridge.app.model

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/** Lógica da borracha, sem dependências do Android (testável na JVM). */
object Eraser {

    /** Distância de (px,py) ao segmento a–b. */
    fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 <= 1e-6f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** O traço passa a menos de [r] do ponto (x,y)? Conta com a espessura do traço. */
    fun hits(s: Stroke, x: Float, y: Float, r: Float): Boolean {
        val pad = r + s.width
        if (x < s.minX - pad || x > s.maxX + pad || y < s.minY - pad || y > s.maxY + pad) return false
        if (s.count == 1) return hypot(x - s.x(0), y - s.y(0)) <= r + s.widthAt(0) / 2
        for (i in 0 until s.count - 1) {
            val reach = r + max(s.widthAt(i), s.widthAt(i + 1)) / 2
            if (segDist(x, y, s.x(i), s.y(i), s.x(i + 1), s.y(i + 1)) <= reach) return true
        }
        return false
    }

    /** Insere pontos para que dois pontos seguidos nunca fiquem a mais de [maxStep]. */
    fun densify(pts: FloatArray, maxStep: Float): FloatArray {
        val n = pts.size / 3
        if (n < 2) return pts
        val out = ArrayList<Float>(pts.size * 2)
        for (i in 0 until n) {
            val x = pts[i * 3]; val y = pts[i * 3 + 1]; val p = pts[i * 3 + 2]
            if (i > 0) {
                val px = pts[i * 3 - 3]; val py = pts[i * 3 - 2]; val pp = pts[i * 3 - 1]
                val d = hypot(x - px, y - py)
                val steps = (d / maxStep).toInt()
                for (k in 1..steps) {
                    val t = k / (steps + 1f)
                    out.add(px + (x - px) * t); out.add(py + (y - py) * t); out.add(pp + (p - pp) * t)
                }
            }
            out.add(x); out.add(y); out.add(p)
        }
        return out.toFloatArray()
    }

    /**
     * Borracha parcial: corta o traço onde a borracha passa.
     * Devolve os pedaços que sobram (pode ser lista vazia).
     */
    fun split(s: Stroke, x: Float, y: Float, r: Float): List<Stroke> {
        val dense = densify(s.pts, max(r / 2f, 0.5f))
        val n = dense.size / 3
        val pieces = ArrayList<Stroke>()
        var start = -1
        val reach = r + s.width / 2
        val reach2 = reach * reach
        fun flush(end: Int) {
            if (start >= 0 && end - start >= 2) {
                pieces.add(Stroke(Stroke.newId(), s.kind, s.color, s.width, dense.copyOfRange(start * 3, end * 3)))
            }
            start = -1
        }
        for (i in 0 until n) {
            val dx = dense[i * 3] - x; val dy = dense[i * 3 + 1] - y
            val inside = dx * dx + dy * dy <= reach2
            if (inside) flush(i) else if (start < 0) start = i
        }
        flush(n)
        return pieces
    }

    @Suppress("unused")
    fun dist(ax: Float, ay: Float, bx: Float, by: Float) = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
}

/**
 * Acumula o efeito de um gesto de borracha (do pousar ao levantar a caneta),
 * para que o desfazer anule o gesto inteiro de uma vez.
 */
class EraseSession(private val page: Page, private val partial: Boolean) {
    private val removedOriginal = LinkedHashMap<String, Stroke>()
    private val addedLive = LinkedHashMap<String, Stroke>()

    /** Resultado de um passo: ids a apagar e traços novos (para enviar ao PC). */
    class Step(val removedIds: List<String>, val added: List<Stroke>) {
        val isEmpty get() = removedIds.isEmpty() && added.isEmpty()
    }

    fun eraseAt(x: Float, y: Float, r: Float): Step {
        val removedIds = ArrayList<String>()
        val added = ArrayList<Stroke>()
        val it = page.strokes.listIterator()
        while (it.hasNext()) {
            val s = it.next()
            if (!Eraser.hits(s, x, y, r)) continue
            it.remove()
            removedIds.add(s.id)
            if (addedLive.remove(s.id) == null) removedOriginal[s.id] = s
            if (partial) {
                for (piece in Eraser.split(s, x, y, r)) {
                    it.add(piece)
                    added.add(piece)
                    addedLive[piece.id] = piece
                }
            }
        }
        return Step(removedIds, added)
    }

    /** Operação total do gesto, ou null se nada mudou. */
    fun toOp(): Op? =
        if (removedOriginal.isEmpty() && addedLive.isEmpty()) null
        else Op(page.id, removedOriginal.values.toList(), addedLive.values.toList())
}
