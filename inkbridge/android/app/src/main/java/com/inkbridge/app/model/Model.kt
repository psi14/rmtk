package com.inkbridge.app.model

import java.util.UUID

/** Tipo de traço guardado no caderno. */
enum class StrokeKind(val key: String) {
    PEN("pen"), HIGHLIGHTER("hl");

    companion object {
        fun fromKey(k: String?): StrokeKind = entries.firstOrNull { it.key == k } ?: PEN
    }
}

/** Fundo da página. */
enum class Template(val key: String, val label: String) {
    BLANK("blank", "Em branco"),
    LINED("lined", "Pautado"),
    GRID("grid", "Quadriculado"),
    DOTS("dots", "Pontos");

    companion object {
        fun fromKey(k: String?): Template = entries.firstOrNull { it.key == k } ?: BLANK
    }
}

/** Formatos de página (unidades lógicas; a largura de A4 vale 1000). */
enum class PageFormat(val label: String, val w: Float, val h: Float) {
    A4_PORTRAIT("A4 vertical", 1000f, 1414f),
    A4_LANDSCAPE("A4 horizontal", 1414f, 1000f),
    WIDE("Ecrã 16:10", 1600f, 1000f);
}

/** Constantes de desenho dos fundos, iguais no tablet e no PC. */
object TemplateSpec {
    const val LINE_SPACING = 36f
    const val LINE_TOP = 120f
    const val MARGIN_X = 90f
    const val GRID_SPACING = 30f
    const val DOT_SPACING = 30f
    const val DOT_RADIUS = 1.6f
    const val LINE_WIDTH = 1f
    const val LINE_COLOR = 0xFFC9D3E6.toInt()
    const val MARGIN_COLOR = 0xFFF2B8B8.toInt()
    const val HIGHLIGHTER_ALPHA = 0x60
}

/**
 * Um traço. Os pontos estão em [pts] como triplos (x, y, pressão),
 * em unidades da página. Depois de criado, um traço não muda.
 */
class Stroke(
    val id: String,
    val kind: StrokeKind,
    val color: Int,
    val width: Float,
    val pts: FloatArray,
) {
    val count: Int get() = pts.size / 3
    fun x(i: Int) = pts[i * 3]
    fun y(i: Int) = pts[i * 3 + 1]
    fun p(i: Int) = pts[i * 3 + 2]

    val minX: Float
    val minY: Float
    val maxX: Float
    val maxY: Float

    init {
        var a = Float.MAX_VALUE; var b = Float.MAX_VALUE
        var c = -Float.MAX_VALUE; var d = -Float.MAX_VALUE
        for (i in 0 until count) {
            val x = x(i); val y = y(i)
            if (x < a) a = x; if (y < b) b = y
            if (x > c) c = x; if (y > d) d = y
        }
        if (count == 0) { a = 0f; b = 0f; c = 0f; d = 0f }
        minX = a; minY = b; maxX = c; maxY = d
    }

    /** Largura real num ponto, consoante a pressão. */
    fun widthAt(i: Int): Float = widthFor(kind, width, p(i))

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 12)

        /** Mesma fórmula no PC: a pressão só afeta a caneta, não o marcador. */
        fun widthFor(kind: StrokeKind, base: Float, pressure: Float): Float =
            if (kind == StrokeKind.HIGHLIGHTER) base
            else base * (0.35f + 0.65f * pressure.coerceIn(0f, 1f))
    }
}

class Page(
    val id: String = Stroke.newId(),
    var template: Template = Template.LINED,
    var w: Float = PageFormat.A4_PORTRAIT.w,
    var h: Float = PageFormat.A4_PORTRAIT.h,
    val strokes: MutableList<Stroke> = ArrayList(),
) {
    fun indexOfStroke(id: String) = strokes.indexOfFirst { it.id == id }
}

class Notebook(
    val id: String = Stroke.newId(),
    var title: String = "Caderno",
    val pages: MutableList<Page> = mutableListOf(Page()),
    var current: Int = 0,
    var modified: Long = System.currentTimeMillis(),
) {
    val currentPage: Page get() = pages[current.coerceIn(0, pages.size - 1)]
    fun pageById(id: String) = pages.firstOrNull { it.id == id }
}

/**
 * Uma alteração reversível numa página: remove [removed] e junta [added].
 * Desfazer = aplicar o inverso.
 */
class Op(val pageId: String, val removed: List<Stroke>, val added: List<Stroke>) {
    fun inverse() = Op(pageId, added, removed)

    fun applyTo(page: Page) {
        if (removed.isNotEmpty()) {
            val ids = removed.mapTo(HashSet()) { it.id }
            page.strokes.removeAll { it.id in ids }
        }
        page.strokes.addAll(added)
    }
}

/** Pilha de desfazer/refazer de um caderno. */
class History(private val limit: Int = 200) {
    private val undo = ArrayDeque<Op>()
    private val redo = ArrayDeque<Op>()

    fun push(op: Op) {
        undo.addLast(op)
        if (undo.size > limit) undo.removeFirst()
        redo.clear()
    }

    fun canUndo() = undo.isNotEmpty()
    fun canRedo() = redo.isNotEmpty()

    /** Devolve a operação a aplicar (já invertida), ou null. */
    fun undo(): Op? = undo.removeLastOrNull()?.also { redo.addLast(it) }?.inverse()
    fun redo(): Op? = redo.removeLastOrNull()?.also { undo.addLast(it) }

    fun clear() { undo.clear(); redo.clear() }
}
