package com.inkbridge.app.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {

    private fun line(x0: Float, x1: Float, y: Float = 100f, n: Int = 11): Stroke {
        val pts = FloatArray(n * 3)
        for (i in 0 until n) {
            pts[i * 3] = x0 + (x1 - x0) * i / (n - 1)
            pts[i * 3 + 1] = y
            pts[i * 3 + 2] = 0.5f
        }
        return Stroke(Stroke.newId(), StrokeKind.PEN, 0xFF000000.toInt(), 2f, pts)
    }

    @Test
    fun numberFormatting() {
        fun f(v: Float, d: Int = 2) = StringBuilder().also { Json.num(it, v, d) }.toString()
        assertEquals("0", f(0f))
        assertEquals("1.5", f(1.5f))
        assertEquals("0.05", f(0.05f))
        assertEquals("-3.25", f(-3.25f))
        assertEquals("12", f(12.001f))
        assertEquals("0.333", f(1f / 3f, 3))
    }

    @Test
    fun notebookRoundTrip() {
        val nb = Notebook(title = "Teste \"aspas\" é ã\n")
        nb.pages[0].strokes.add(line(0f, 100f))
        nb.pages.add(Page(template = Template.GRID, w = 1414f, h = 1000f))
        nb.current = 1
        val text = Json.notebookToString(nb)
        JSONObject(text) // tem de ser JSON válido
        val back = Json.parseNotebook(text)
        assertEquals(nb.title, back.title)
        assertEquals(2, back.pages.size)
        assertEquals(1, back.current)
        assertEquals(Template.GRID, back.pages[1].template)
        assertEquals(1414f, back.pages[1].w, 0.001f)
        val s = back.pages[0].strokes[0]
        assertEquals(11, s.count)
        assertEquals(100f, s.x(10), 0.01f)
        assertEquals(0.5f, s.p(3), 0.001f)
    }

    @Test
    fun strokeEraserRemovesWholeStroke() {
        val page = Page()
        val a = line(0f, 100f, y = 100f)
        val b = line(0f, 100f, y = 300f)
        page.strokes.addAll(listOf(a, b))
        val es = EraseSession(page, partial = false)
        val step = es.eraseAt(50f, 101f, 5f)
        assertEquals(listOf(a.id), step.removedIds)
        assertEquals(listOf(b), page.strokes)
        val op = es.toOp()!!
        op.inverse().applyTo(page)
        assertEquals(2, page.strokes.size)
    }

    @Test
    fun partialEraserSplitsStroke() {
        val page = Page()
        val a = line(0f, 100f, n = 2) // só 2 pontos: obriga a densificar
        page.strokes.add(a)
        val es = EraseSession(page, partial = true)
        val step = es.eraseAt(50f, 100f, 5f)
        assertEquals(listOf(a.id), step.removedIds)
        assertEquals(2, page.strokes.size)
        val left = page.strokes[0]; val right = page.strokes[1]
        assertTrue(left.maxX < 50f - 5f)
        assertTrue(right.minX > 50f + 5f)
        // apagar outra vez um pedaço novo, no mesmo gesto
        es.eraseAt(90f, 100f, 3f)
        val op = es.toOp()!!
        assertEquals(1, op.removed.size) // só o original
        assertTrue(op.added.all { s -> page.strokes.any { it.id == s.id } })
        op.inverse().applyTo(page)
        assertEquals(listOf(a.id), page.strokes.map { it.id })
    }

    @Test
    fun eraserMissesFarStroke() {
        val s = line(0f, 100f)
        assertFalse(Eraser.hits(s, 50f, 150f, 10f))
        assertTrue(Eraser.hits(s, 50f, 108f, 10f))
    }

    @Test
    fun historyUndoRedo() {
        val page = Page()
        val h = History()
        val s = line(0f, 10f)
        val op = Op(page.id, emptyList(), listOf(s))
        op.applyTo(page); h.push(op)
        h.undo()!!.applyTo(page)
        assertTrue(page.strokes.isEmpty())
        h.redo()!!.applyTo(page)
        assertEquals(1, page.strokes.size)
        assertFalse(h.canRedo())
    }
}
