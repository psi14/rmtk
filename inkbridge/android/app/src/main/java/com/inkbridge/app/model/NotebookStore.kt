package com.inkbridge.app.model

import java.io.File
import java.util.concurrent.Executors

/** Cadernos guardados como ficheiros JSON na memória interna da app. */
class NotebookStore(baseDir: File) {
    private val dir = File(baseDir, "notebooks").apply { mkdirs() }
    private val io = Executors.newSingleThreadExecutor()

    class Info(val id: String, val title: String, val pages: Int, val modified: Long)

    private fun file(id: String) = File(dir, "$id.json")

    fun list(): List<Info> =
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            try {
                val nb = Json.parseNotebook(f.readText())
                Info(nb.id, nb.title, nb.pages.size, nb.modified)
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.modified }

    fun load(id: String): Notebook? = try {
        Json.parseNotebook(file(id).readText())
    } catch (e: Exception) {
        null
    }

    /** Converte já (na thread atual) e grava em segundo plano, de forma atómica. */
    fun saveAsync(nb: Notebook) {
        nb.modified = System.currentTimeMillis()
        val text = Json.notebookToString(nb)
        val id = nb.id
        io.execute { writeAtomic(id, text) }
    }

    fun saveNow(nb: Notebook) {
        nb.modified = System.currentTimeMillis()
        writeAtomic(nb.id, Json.notebookToString(nb))
    }

    private fun writeAtomic(id: String, text: String) {
        val tmp = File(dir, "$id.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file(id))) {
            file(id).writeText(text)
            tmp.delete()
        }
    }

    fun delete(id: String) {
        file(id).delete()
    }

    fun rename(id: String, title: String) {
        val nb = load(id) ?: return
        nb.title = title
        saveNow(nb)
    }
}
