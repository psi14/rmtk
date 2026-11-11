package com.inkbridge.app.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Leitura/escrita em JSON. O mesmo formato serve para guardar ficheiros
 * e para enviar ao PC (ver PROTOCOL.md).
 */
object Json {

    // ---------- escrita (manual, para ser rápida) ----------

    fun str(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    /** Escreve um número com no máximo [decimals] casas decimais. */
    fun num(sb: StringBuilder, v: Float, decimals: Int = 2) {
        if (v.isNaN() || v.isInfinite()) { sb.append('0'); return }
        var scale = 1L
        repeat(decimals) { scale *= 10 }
        var r = Math.round(v.toDouble() * scale)
        if (r < 0) { sb.append('-'); r = -r }
        val ip = r / scale
        var fp = r % scale
        sb.append(ip)
        if (fp != 0L) {
            sb.append('.')
            var s = scale / 10
            while (fp != 0L && s > 0) {
                sb.append((fp / s).toInt())
                fp %= s
                s /= 10
            }
        }
    }

    fun points(sb: StringBuilder, pts: FloatArray, from: Int = 0, to: Int = pts.size / 3) {
        sb.append('[')
        for (i in from until to) {
            if (i > from) sb.append(',')
            num(sb, pts[i * 3]); sb.append(',')
            num(sb, pts[i * 3 + 1]); sb.append(',')
            num(sb, pts[i * 3 + 2], 3)
        }
        sb.append(']')
    }

    fun stroke(sb: StringBuilder, s: Stroke) {
        sb.append("{\"id\":"); str(sb, s.id)
        sb.append(",\"k\":"); str(sb, s.kind.key)
        sb.append(",\"c\":").append(s.color)
        sb.append(",\"w\":"); num(sb, s.width)
        sb.append(",\"pts\":"); points(sb, s.pts)
        sb.append('}')
    }

    fun strokes(sb: StringBuilder, list: List<Stroke>) {
        sb.append('[')
        list.forEachIndexed { i, s -> if (i > 0) sb.append(','); stroke(sb, s) }
        sb.append(']')
    }

    fun page(sb: StringBuilder, p: Page) {
        sb.append("{\"id\":"); str(sb, p.id)
        sb.append(",\"tpl\":"); str(sb, p.template.key)
        sb.append(",\"w\":"); num(sb, p.w)
        sb.append(",\"h\":"); num(sb, p.h)
        sb.append(",\"strokes\":"); strokes(sb, p.strokes)
        sb.append('}')
    }

    fun notebook(sb: StringBuilder, nb: Notebook) {
        sb.append("{\"id\":"); str(sb, nb.id)
        sb.append(",\"title\":"); str(sb, nb.title)
        sb.append(",\"cur\":").append(nb.current)
        sb.append(",\"modified\":").append(nb.modified)
        sb.append(",\"pages\":[")
        nb.pages.forEachIndexed { i, p -> if (i > 0) sb.append(','); page(sb, p) }
        sb.append("]}")
    }

    fun notebookToString(nb: Notebook): String = StringBuilder(4096).also { notebook(it, nb) }.toString()

    // ---------- leitura ----------

    fun parseStroke(o: JSONObject): Stroke {
        val a = o.getJSONArray("pts")
        val pts = FloatArray(a.length() - a.length() % 3)
        for (i in pts.indices) pts[i] = a.getDouble(i).toFloat()
        return Stroke(
            id = o.getString("id"),
            kind = StrokeKind.fromKey(o.optString("k")),
            color = o.optInt("c", 0xFF000000.toInt()),
            width = o.optDouble("w", 3.0).toFloat(),
            pts = pts,
        )
    }

    fun parsePage(o: JSONObject): Page {
        val arr = o.optJSONArray("strokes") ?: JSONArray()
        val list = ArrayList<Stroke>(arr.length())
        for (i in 0 until arr.length()) list.add(parseStroke(arr.getJSONObject(i)))
        return Page(
            id = o.getString("id"),
            template = Template.fromKey(o.optString("tpl")),
            w = o.optDouble("w", 1000.0).toFloat(),
            h = o.optDouble("h", 1414.0).toFloat(),
            strokes = list,
        )
    }

    fun parseNotebook(text: String): Notebook {
        val o = JSONObject(text)
        val arr = o.getJSONArray("pages")
        val pages = ArrayList<Page>(arr.length())
        for (i in 0 until arr.length()) pages.add(parsePage(arr.getJSONObject(i)))
        if (pages.isEmpty()) pages.add(Page())
        return Notebook(
            id = o.getString("id"),
            title = o.optString("title", "Caderno"),
            pages = pages,
            current = o.optInt("cur", 0).coerceIn(0, pages.size - 1),
            modified = o.optLong("modified", System.currentTimeMillis()),
        )
    }
}
