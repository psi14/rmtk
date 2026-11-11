package com.inkbridge.app.model

import com.inkbridge.app.net.Protocol
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Gera uma sessão de exemplo com o código real do protocolo e grava-a em
 * build/protocol-sample.jsonl (usada para testar a app do PC).
 */
class ProtocolTest {
    @Test
    fun sampleSession() {
        val nb = Notebook(title = "Teste do protocolo")
        val page = nb.pages[0]
        val lines = ArrayList<String>()
        lines += Protocol.hello("TCL NXTPAPER (teste JVM)")
        lines += Protocol.doc(nb)
        val n = 120
        val pts = FloatArray(n * 3)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            pts[i * 3] = 120f + 760f * t
            pts[i * 3 + 1] = 400f + 60f * kotlin.math.sin(t * 6.28f * 2)
            pts[i * 3 + 2] = kotlin.math.sin(t * 3.14f)
        }
        lines += Protocol.strokeBegin(page.id, "k1", StrokeKind.PEN, 0xFF1E3A8A.toInt(), 5f)
        var i = 0
        while (i < n) { val j = minOf(n, i + 7); lines += Protocol.strokePoints("k1", pts, i, j); i = j }
        lines += Protocol.strokeEnd("k1")
        val s2 = Stroke("k2", StrokeKind.HIGHLIGHTER, 0xFFFACC15.toInt(), 22f, floatArrayOf(120f, 520f, 1f, 880f, 520f, 1f))
        lines += Protocol.add(page.id, listOf(s2))
        lines += Protocol.delete(page.id, listOf("nao-existe"))
        lines += Protocol.hover(500f, 600f, true)
        lines += Protocol.PING

        for (l in lines) assertEquals(false, l.contains('\n'))
        assertEquals("sp", JSONObject(lines[3]).getString("t"))
        File("build").mkdirs()
        File("build/protocol-sample.jsonl").writeText(lines.joinToString("\n") + "\n")
    }
}
