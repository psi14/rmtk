package com.inkbridge.app.net

import com.inkbridge.app.model.Json
import com.inkbridge.app.model.Notebook
import com.inkbridge.app.model.Stroke
import com.inkbridge.app.model.StrokeKind

/**
 * Mensagens enviadas ao PC: uma linha JSON por mensagem (ver PROTOCOL.md).
 */
object Protocol {
    const val VERSION = 1
    const val TCP_PORT = 47800
    const val UDP_PORT = 47801

    private inline fun msg(type: String, body: StringBuilder.() -> Unit): String {
        val sb = StringBuilder(128)
        sb.append("{\"t\":\"").append(type).append('"')
        sb.body()
        sb.append('}')
        return sb.toString()
    }

    fun hello(device: String) = msg("hello") {
        append(",\"device\":"); Json.str(this, device)
        append(",\"v\":").append(VERSION)
    }

    fun doc(nb: Notebook) = msg("doc") { append(",\"nb\":"); Json.notebook(this, nb) }

    fun current(index: Int) = msg("cur") { append(",\"i\":").append(index) }

    fun strokeBegin(pageId: String, id: String, kind: StrokeKind, color: Int, width: Float) = msg("sb") {
        append(",\"pg\":"); Json.str(this, pageId)
        append(",\"id\":"); Json.str(this, id)
        append(",\"k\":"); Json.str(this, kind.key)
        append(",\"c\":").append(color)
        append(",\"w\":"); Json.num(this, width)
    }

    fun strokePoints(id: String, pts: FloatArray, from: Int, to: Int) = msg("sp") {
        append(",\"id\":"); Json.str(this, id)
        append(",\"pts\":"); Json.points(this, pts, from, to)
    }

    fun strokeEnd(id: String) = msg("se") { append(",\"id\":"); Json.str(this, id) }

    fun strokeCancel(id: String) = msg("sc") { append(",\"id\":"); Json.str(this, id) }

    fun delete(pageId: String, ids: List<String>) = msg("del") {
        append(",\"pg\":"); Json.str(this, pageId)
        append(",\"ids\":[")
        ids.forEachIndexed { i, s -> if (i > 0) append(','); Json.str(this, s) }
        append(']')
    }

    fun add(pageId: String, strokes: List<Stroke>) = msg("add") {
        append(",\"pg\":"); Json.str(this, pageId)
        append(",\"strokes\":"); Json.strokes(this, strokes)
    }

    fun hover(x: Float, y: Float, on: Boolean) = msg("hv") {
        append(",\"x\":"); Json.num(this, x, 1)
        append(",\"y\":"); Json.num(this, y, 1)
        append(",\"on\":").append(if (on) 1 else 0)
    }

    const val PING = "{\"t\":\"ping\"}"
}
