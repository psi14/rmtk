package com.inkbridge.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.inkbridge.app.R
import com.inkbridge.app.ink.InkView
import com.inkbridge.app.ink.Painter
import com.inkbridge.app.ink.Tool
import com.inkbridge.app.model.EraseSession
import com.inkbridge.app.model.History
import com.inkbridge.app.model.Notebook
import com.inkbridge.app.model.Op
import com.inkbridge.app.model.Page
import com.inkbridge.app.model.PageFormat
import com.inkbridge.app.model.Stroke
import com.inkbridge.app.model.StrokeKind
import com.inkbridge.app.model.Template
import com.inkbridge.app.net.Link
import com.inkbridge.app.net.Protocol

class EditorActivity : AppCompatActivity(), InkView.Listener {

    companion object {
        const val EXTRA_ID = "notebook_id"
        val PALETTE = intArrayOf(
            0xFF000000.toInt(), 0xFF4B5563.toInt(), 0xFF1E3A8A.toInt(), 0xFF2563EB.toInt(),
            0xFFDC2626.toInt(), 0xFF16A34A.toInt(), 0xFFEA580C.toInt(), 0xFF9333EA.toInt(),
            0xFFFACC15.toInt(), 0xFFEC4899.toInt(),
        )
    }

    private lateinit var nb: Notebook
    private val history = History()
    private lateinit var ink: InkView
    private lateinit var pageLabel: TextView
    private lateinit var linkStatus: TextView
    private lateinit var widthBar: SeekBar
    private lateinit var widthLabel: TextView
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private val toolButtons = HashMap<Tool, ImageButton>()
    private val swatches = ArrayList<View>()

    private val handler = Handler(Looper.getMainLooper())
    private val saveTask = Runnable { save() }
    private val linkListener = { updateLinkStatus() }
    private var connectDialogAdapter: ArrayAdapter<String>? = null
    private var connectDialogTargets: List<Link.Target> = emptyList()

    // definições de cada ferramenta (guardadas entre sessões)
    private var penProgress = 6       // largura = progress * 0.5
    private var hlProgress = 15       // largura = progress * 1.5
    private var eraserProgress = 11   // raio = progress * 2 px
    private var penColor = PALETTE[0]
    private var hlColor = PALETTE[8]

    private val prefs by lazy { getSharedPreferences("inkbridge", Context.MODE_PRIVATE) }

    private val exportPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) writePdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        val loaded = id?.let { Stores.get(this).load(it) }
        if (loaded == null) { finish(); return }
        nb = loaded

        setContentView(R.layout.activity_editor)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        ink = findViewById(R.id.ink)
        pageLabel = findViewById(R.id.pageLabel)
        linkStatus = findViewById(R.id.linkStatus)
        widthBar = findViewById(R.id.widthBar)
        widthLabel = findViewById(R.id.widthLabel)
        btnUndo = findViewById(R.id.btnUndo)
        btnRedo = findViewById(R.id.btnRedo)
        findViewById<TextView>(R.id.title).apply {
            text = nb.title
            setOnClickListener { renameNotebook() }
        }

        loadPrefs()
        ink.listener = this
        ink.page = nb.currentPage

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        toolButtons[Tool.PEN] = findViewById(R.id.btnPen)
        toolButtons[Tool.HIGHLIGHTER] = findViewById(R.id.btnHighlighter)
        toolButtons[Tool.ERASER] = findViewById(R.id.btnEraser)
        toolButtons.forEach { (tool, btn) ->
            btn.setOnClickListener {
                if (ink.tool == tool && tool == Tool.ERASER) toggleEraserMode() else selectTool(tool)
            }
        }
        buildPalette()

        widthBar.max = 40
        widthBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val p = progress.coerceAtLeast(1)
                when (ink.tool) {
                    Tool.PEN -> penProgress = p
                    Tool.HIGHLIGHTER -> hlProgress = p
                    Tool.ERASER -> eraserProgress = p
                }
                applyToolSettings()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { savePrefs() }
        })

        btnUndo.setOnClickListener { onUndoGesture() }
        btnRedo.setOnClickListener { onRedoGesture() }
        findViewById<View>(R.id.btnPrev).setOnClickListener { goToPage(nb.current - 1) }
        findViewById<View>(R.id.btnNext).setOnClickListener {
            if (nb.current == nb.pages.size - 1) addPage() else goToPage(nb.current + 1)
        }
        findViewById<View>(R.id.btnAddPage).setOnClickListener { addPage() }
        findViewById<View>(R.id.btnTemplate).setOnClickListener { chooseTemplate() }
        findViewById<View>(R.id.btnLink).setOnClickListener { showConnectDialog() }
        linkStatus.setOnClickListener { showConnectDialog() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showMoreMenu(it) }

        selectTool(Tool.valueOf(prefs.getString("tool", Tool.PEN.name) ?: Tool.PEN.name))
        updatePageLabel()
        updateUndoButtons()
    }

    override fun onResume() {
        super.onResume()
        Link.snapshotProvider = { Protocol.doc(nb) }
        Link.addListener(linkListener)
        Link.onDiscovered = { d -> onPcDiscovered(d) }
        Link.startDiscovery(this)
        autoConnect()
        Link.resync()
        updateLinkStatus()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(saveTask)
        save()
        Link.removeListener(linkListener)
        Link.onDiscovered = null
        Link.stopDiscovery()
        savePrefs()
    }

    // ------------------------------------------------------------ ferramentas

    private fun loadPrefs() {
        penProgress = prefs.getInt("pen_w", penProgress)
        hlProgress = prefs.getInt("hl_w", hlProgress)
        eraserProgress = prefs.getInt("er_w", eraserProgress)
        penColor = prefs.getInt("pen_c", penColor)
        hlColor = prefs.getInt("hl_c", hlColor)
        ink.partialEraser = prefs.getBoolean("partial", true)
        ink.fingerDraws = prefs.getBoolean("finger", false)
    }

    private fun savePrefs() {
        prefs.edit()
            .putInt("pen_w", penProgress).putInt("hl_w", hlProgress).putInt("er_w", eraserProgress)
            .putInt("pen_c", penColor).putInt("hl_c", hlColor)
            .putBoolean("partial", ink.partialEraser).putBoolean("finger", ink.fingerDraws)
            .putString("tool", ink.tool.name)
            .apply()
    }

    private fun selectTool(tool: Tool) {
        ink.tool = tool
        toolButtons.forEach { (t, b) -> b.isSelected = t == tool }
        widthBar.progress = when (tool) {
            Tool.PEN -> penProgress
            Tool.HIGHLIGHTER -> hlProgress
            Tool.ERASER -> eraserProgress
        }
        applyToolSettings()
        if (tool == Tool.ERASER) toast(if (ink.partialEraser) "Borracha parcial (toque outra vez para borracha de traço)" else "Borracha de traço (toque outra vez para borracha parcial)")
    }

    private fun toggleEraserMode() {
        ink.partialEraser = !ink.partialEraser
        toast(if (ink.partialEraser) "Borracha parcial" else "Borracha de traço inteiro")
        savePrefs()
    }

    private fun applyToolSettings() {
        ink.penWidth = penProgress * 0.5f
        ink.highlighterWidth = hlProgress * 1.5f
        ink.eraserRadiusPx = eraserProgress * 2f * resources.displayMetrics.density / 1.5f
        ink.color = if (ink.tool == Tool.HIGHLIGHTER) hlColor else penColor
        widthLabel.text = when (ink.tool) {
            Tool.PEN -> String.format(java.util.Locale.getDefault(), "%.1f", ink.penWidth)
            Tool.HIGHLIGHTER -> String.format(java.util.Locale.getDefault(), "%.0f", ink.highlighterWidth)
            Tool.ERASER -> "${eraserProgress * 2}"
        }
        updateSwatches()
    }

    private fun buildPalette() {
        val box = findViewById<LinearLayout>(R.id.colors)
        val size = dp(28f).toInt()
        for (c in PALETTE) {
            val v = View(this)
            v.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(4f).toInt(); marginEnd = dp(4f).toInt()
            }
            v.tag = c
            v.setOnClickListener {
                if (ink.tool == Tool.HIGHLIGHTER) hlColor = c else {
                    penColor = c
                    if (ink.tool == Tool.ERASER) selectTool(Tool.PEN)
                }
                applyToolSettings()
                savePrefs()
            }
            swatches.add(v)
            box.addView(v)
        }
        updateSwatches()
    }

    private fun updateSwatches() {
        val current = if (ink.tool == Tool.HIGHLIGHTER) hlColor else penColor
        for (v in swatches) {
            val c = v.tag as Int
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                if (c == current) setStroke(dp(3f).toInt(), 0xFF93C5FD.toInt())
                else setStroke(dp(1f).toInt(), 0x33000000)
            }
        }
    }

    // ------------------------------------------------------------ páginas

    private fun goToPage(i: Int) {
        if (i < 0 || i >= nb.pages.size || i == nb.current) return
        nb.current = i
        ink.page = nb.currentPage
        updatePageLabel()
        Link.send(Protocol.current(i))
        scheduleSave()
    }

    private fun addPage() {
        val cur = nb.currentPage
        val p = Page(template = cur.template, w = cur.w, h = cur.h)
        nb.pages.add(nb.current + 1, p)
        nb.current += 1
        ink.page = p
        updatePageLabel()
        Link.resync()
        scheduleSave()
    }

    private fun deletePage() {
        AlertDialog.Builder(this)
            .setTitle("Apagar a página ${nb.current + 1}?")
            .setMessage("Esta ação não pode ser desfeita.")
            .setPositiveButton("Apagar") { _, _ ->
                if (nb.pages.size == 1) {
                    nb.pages[0].strokes.clear()
                } else {
                    nb.pages.removeAt(nb.current)
                    nb.current = nb.current.coerceAtMost(nb.pages.size - 1)
                }
                history.clear()
                updateUndoButtons()
                ink.page = nb.currentPage
                updatePageLabel()
                Link.resync()
                scheduleSave()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun chooseTemplate() {
        val list = Template.entries
        AlertDialog.Builder(this)
            .setTitle("Fundo da página")
            .setSingleChoiceItems(list.map { it.label }.toTypedArray(), list.indexOf(nb.currentPage.template)) { d, which ->
                nb.currentPage.template = list[which]
                ink.onPageContentChanged()
                Link.resync()
                scheduleSave()
                d.dismiss()
            }
            .show()
    }

    private fun chooseFormat() {
        val list = PageFormat.entries
        val cur = nb.currentPage
        val idx = list.indexOfFirst { it.w == cur.w && it.h == cur.h }
        AlertDialog.Builder(this)
            .setTitle("Formato da página")
            .setSingleChoiceItems(list.map { it.label }.toTypedArray(), idx) { d, which ->
                cur.w = list[which].w
                cur.h = list[which].h
                ink.page = cur
                Link.resync()
                scheduleSave()
                d.dismiss()
            }
            .show()
    }

    private fun updatePageLabel() {
        pageLabel.text = "${nb.current + 1}/${nb.pages.size}"
    }

    private fun renameNotebook() {
        val input = EditText(this).apply {
            setText(nb.title)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Nome do caderno")
            .setView(FrameLayout(this).apply {
                val pad = dp(20f).toInt(); setPadding(pad, pad / 2, pad, 0); addView(input)
            })
            .setPositiveButton("Guardar") { _, _ ->
                nb.title = input.text.toString().ifBlank { nb.title }
                findViewById<TextView>(R.id.title).text = nb.title
                Link.resync()
                scheduleSave()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showMoreMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Exportar caderno para PDF")
        m.menu.add(0, 2, 1, "Formato da página…")
        m.menu.add(0, 3, 2, "Borracha parcial").apply { isCheckable = true; isChecked = ink.partialEraser }
        m.menu.add(0, 4, 3, "Desenhar com o dedo").apply { isCheckable = true; isChecked = ink.fingerDraws }
        m.menu.add(0, 5, 4, "Ajustar página ao ecrã")
        m.menu.add(0, 6, 5, "Mudar o nome do caderno")
        m.menu.add(0, 7, 6, "Apagar esta página")
        m.menu.add(0, 8, 7, "Ajuda: gestos")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> exportPdf.launch(nb.title.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".pdf")
                2 -> chooseFormat()
                3 -> { ink.partialEraser = !ink.partialEraser; savePrefs() }
                4 -> { ink.fingerDraws = !ink.fingerDraws; savePrefs() }
                5 -> ink.fitWidth()
                6 -> renameNotebook()
                7 -> deletePage()
                8 -> AlertDialog.Builder(this).setTitle("Gestos").setMessage(
                    "• Caneta: escreve. Botão lateral da caneta (carregado): borracha.\n" +
                        "• 1 dedo: arrasta a página. 2 dedos: zoom.\n" +
                        "• Toque rápido com 2 dedos: desfazer. Com 3 dedos: refazer.\n" +
                        "• Toque outra vez na borracha: muda entre borracha parcial e de traço inteiro.\n" +
                        "• A palma da mão é ignorada.",
                ).setPositiveButton("OK", null).show()
            }
            true
        }
        m.show()
    }

    // ------------------------------------------------------------ desfazer

    private fun applyOp(op: Op) {
        val page = nb.pageById(op.pageId) ?: return
        val idx = nb.pages.indexOf(page)
        if (idx != nb.current) {
            nb.current = idx
            ink.page = page
            updatePageLabel()
            Link.send(Protocol.current(idx))
        }
        op.applyTo(page)
        ink.onPageContentChanged()
        if (op.removed.isNotEmpty()) Link.send(Protocol.delete(page.id, op.removed.map { it.id }))
        if (op.added.isNotEmpty()) Link.send(Protocol.add(page.id, op.added))
        updateUndoButtons()
        scheduleSave()
    }

    override fun onUndoGesture() { history.undo()?.let { applyOp(it) } }
    override fun onRedoGesture() { history.redo()?.let { applyOp(it) } }

    private fun updateUndoButtons() {
        btnUndo.alpha = if (history.canUndo()) 1f else 0.35f
        btnRedo.alpha = if (history.canRedo()) 1f else 0.35f
    }

    // ------------------------------------------------------------ eventos da tela

    override fun onStrokeBegin(page: Page, id: String, kind: StrokeKind, color: Int, width: Float) {
        Link.send(Protocol.strokeBegin(page.id, id, kind, color, width))
    }

    override fun onStrokePoints(id: String, pts: FloatArray, from: Int, to: Int) {
        Link.send(Protocol.strokePoints(id, pts, from, to))
    }

    override fun onStrokeEnd(page: Page, stroke: Stroke) {
        Link.send(Protocol.strokeEnd(stroke.id))
        history.push(Op(page.id, emptyList(), listOf(stroke)))
        updateUndoButtons()
        scheduleSave()
    }

    override fun onStrokeCancel(id: String) {
        Link.send(Protocol.strokeCancel(id))
    }

    override fun onEraseStep(page: Page, step: EraseSession.Step) {
        if (step.removedIds.isNotEmpty()) Link.send(Protocol.delete(page.id, step.removedIds))
        if (step.added.isNotEmpty()) Link.send(Protocol.add(page.id, step.added))
    }

    override fun onEraseEnd(page: Page, op: Op?) {
        if (op != null) {
            history.push(op)
            updateUndoButtons()
            scheduleSave()
        }
    }

    override fun onHover(x: Float, y: Float, on: Boolean) {
        Link.send(Protocol.hover(x, y, on))
    }

    // ------------------------------------------------------------ guardar / exportar

    private fun scheduleSave() {
        handler.removeCallbacks(saveTask)
        handler.postDelayed(saveTask, 1500)
    }

    private fun save() {
        if (::nb.isInitialized) Stores.get(this).saveAsync(nb)
    }

    private fun writePdf(uri: Uri) {
        val doc = PdfDocument()
        val painter = Painter()
        try {
            nb.pages.forEachIndexed { i, p ->
                // A4 = 595 pt de largura para 1000 unidades
                val k = 0.595f
                val info = PdfDocument.PageInfo.Builder((p.w * k).toInt(), (p.h * k).toInt(), i + 1).create()
                val page = doc.startPage(info)
                painter.drawPageBackground(page.canvas, p, k, 0f, 0f, withShadow = false)
                painter.drawStrokes(page.canvas, p, k, 0f, 0f)
                doc.finishPage(page)
            }
            contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
            toast("PDF exportado (${nb.pages.size} páginas)")
        } catch (e: Exception) {
            toast("Erro ao exportar: ${e.message}")
        } finally {
            doc.close()
        }
    }

    // ------------------------------------------------------------ ligação ao PC

    private fun updateLinkStatus() {
        linkStatus.text = when (Link.state) {
            Link.State.CONNECTED -> "● " + (Link.serverName ?: Link.target?.name ?: "PC") + if (Link.target?.isUsb == true) " (USB)" else ""
            Link.State.CONNECTING -> "A ligar…"
            Link.State.OFF -> "Sem PC"
        }
        linkStatus.setTextColor(
            when (Link.state) {
                Link.State.CONNECTED -> 0xFF15803D.toInt()
                Link.State.CONNECTING -> 0xFFB45309.toInt()
                Link.State.OFF -> 0xFF6B7280.toInt()
            },
        )
        refreshConnectDialog()
    }

    private fun autoConnect() {
        if (Link.state != Link.State.OFF) return
        if (!prefs.getBoolean("auto", false)) return
        val host = prefs.getString("last_host", null) ?: return
        val name = prefs.getString("last_name", "PC") ?: "PC"
        Link.connect(Link.Target(name, host, prefs.getInt("last_port", Protocol.TCP_PORT)))
    }

    private fun onPcDiscovered(d: Link.Discovered) {
        refreshConnectDialog()
        // o PC mudou de IP? volta a ligar ao novo endereço
        val t = Link.target
        if (t != null && !t.isUsb && Link.state != Link.State.CONNECTED &&
            t.name == d.name && t.host != d.host
        ) {
            connectTo(Link.Target(d.name, d.host, d.port))
        }
    }

    private fun connectTo(t: Link.Target) {
        prefs.edit().putString("last_host", t.host).putString("last_name", t.name)
            .putInt("last_port", t.port).putBoolean("auto", true).apply()
        Link.connect(t)
    }

    private fun refreshConnectDialog() {
        val a = connectDialogAdapter ?: return
        val targets = ArrayList<Link.Target>()
        val labels = ArrayList<String>()
        for (d in Link.discovered()) {
            targets.add(Link.Target(d.name, d.host, d.port))
            val mark = if (Link.state == Link.State.CONNECTED && Link.target?.host == d.host) "  ✓" else ""
            labels.add("Wi-Fi: ${d.name}  (${d.host})$mark")
        }
        targets.add(Link.USB_TARGET)
        labels.add("Cabo USB" + if (Link.state == Link.State.CONNECTED && Link.target?.isUsb == true) "  ✓" else "")
        connectDialogTargets = targets
        a.clear()
        a.addAll(labels)
        a.notifyDataSetChanged()
    }

    private fun showConnectDialog() {
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        connectDialogAdapter = adapter
        refreshConnectDialog()
        val status = when (Link.state) {
            Link.State.CONNECTED -> "Ligado a ${Link.serverName ?: Link.target?.name}."
            Link.State.CONNECTING -> "A tentar ligar a ${Link.target?.name} (${Link.target?.host})…"
            Link.State.OFF -> "Abra a app InkBridge no PC. Os PCs na mesma rede Wi-Fi aparecem aqui sozinhos."
        }
        val list = android.widget.ListView(this).apply { this.adapter = adapter }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20f).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(this@EditorActivity).apply {
                text = status
                textSize = 14f
                setTextColor(0xFF4B5563.toInt())
                setPadding(0, 0, 0, pad / 2)
            })
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(220f).toInt()))
        }
        var dialog: AlertDialog? = null
        list.setOnItemClickListener { _, _, which, _ ->
            connectDialogTargets.getOrNull(which)?.let { connectTo(it) }
            dialog?.dismiss()
        }
        dialog = AlertDialog.Builder(this)
            .setTitle("Ligar ao PC")
            .setView(box)
            .setNeutralButton("IP manual…") { _, _ -> askManualIp() }
            .setNegativeButton("Desligar") { _, _ ->
                prefs.edit().putBoolean("auto", false).apply()
                Link.disconnect()
            }
            .setPositiveButton("Fechar", null)
            .setOnDismissListener { connectDialogAdapter = null }
            .show()
    }

    private fun askManualIp() {
        val input = EditText(this).apply {
            hint = "ex.: 192.168.1.20"
            setText(prefs.getString("last_host", "") ?: "")
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Endereço IP do PC")
            .setMessage("Aparece na janela da app do PC.")
            .setView(FrameLayout(this).apply {
                val pad = dp(20f).toInt(); setPadding(pad, 0, pad, 0); addView(input)
            })
            .setPositiveButton("Ligar") { _, _ ->
                val host = input.text.toString().trim()
                if (host.isNotEmpty()) connectTo(Link.Target("PC $host", host, Protocol.TCP_PORT))
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Suppress("unused")
    private fun isDark(c: Int) = Color.luminance(c) < 0.5f
}
