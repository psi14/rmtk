package com.inkbridge.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.inkbridge.app.R
import com.inkbridge.app.model.Notebook
import com.inkbridge.app.model.NotebookStore
import com.inkbridge.app.net.Link
import java.text.DateFormat
import java.util.Date

object Stores {
    private var store: NotebookStore? = null
    fun get(ctx: Context): NotebookStore =
        store ?: NotebookStore(ctx.applicationContext.filesDir).also { store = it }
}

fun Context.dp(v: Float) = v * resources.displayMetrics.density

class LibraryActivity : AppCompatActivity() {
    private lateinit var grid: GridView
    private lateinit var empty: TextView
    private lateinit var linkStatus: TextView
    private var items: List<NotebookStore.Info> = emptyList()
    private val linkListener = { updateLink() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        grid = findViewById(R.id.grid)
        empty = findViewById(R.id.empty)
        linkStatus = findViewById(R.id.linkStatus)
        findViewById<View>(R.id.btnNew).setOnClickListener { askNew() }
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> open(items[pos].id) }
        grid.setOnItemLongClickListener { _, _, pos, _ -> manage(items[pos]); true }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        Link.addListener(linkListener)
        updateLink()
    }

    override fun onPause() {
        super.onPause()
        Link.removeListener(linkListener)
    }

    private fun updateLink() {
        linkStatus.text = when (Link.state) {
            Link.State.CONNECTED -> "Ligado a ${Link.serverName ?: Link.target?.name ?: "PC"}"
            Link.State.CONNECTING -> "A ligar ao PC…"
            Link.State.OFF -> ""
        }
    }

    private fun refresh() {
        items = Stores.get(this).list()
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun open(id: String) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, id))
    }

    private fun nameInput(initial: String): EditText = EditText(this).apply {
        setText(initial)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setSelection(text.length)
    }

    private fun wrap(v: View): View = FrameLayout(this).apply {
        val pad = dp(20f).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(v)
    }

    private fun askNew() {
        val input = nameInput("Caderno ${items.size + 1}")
        AlertDialog.Builder(this)
            .setTitle("Novo caderno")
            .setView(wrap(input))
            .setPositiveButton("Criar") { _, _ ->
                val nb = Notebook(title = input.text.toString().ifBlank { "Caderno" })
                Stores.get(this).saveNow(nb)
                open(nb.id)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun manage(info: NotebookStore.Info) {
        AlertDialog.Builder(this)
            .setTitle(info.title)
            .setItems(arrayOf("Mudar o nome", "Apagar")) { _, which ->
                when (which) {
                    0 -> {
                        val input = nameInput(info.title)
                        AlertDialog.Builder(this)
                            .setTitle("Mudar o nome")
                            .setView(wrap(input))
                            .setPositiveButton("Guardar") { _, _ ->
                                Stores.get(this).rename(info.id, input.text.toString().ifBlank { info.title })
                                refresh()
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                    }
                    1 -> AlertDialog.Builder(this)
                        .setTitle("Apagar «${info.title}»?")
                        .setMessage("Esta ação não pode ser desfeita.")
                        .setPositiveButton("Apagar") { _, _ ->
                            Stores.get(this).delete(info.id)
                            grid.postDelayed({ refresh() }, 150)
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
            .show()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val ctx = parent.context
            val card = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val pad = ctx.dp(14f).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    setColor(0xFFFFFFFF.toInt())
                    cornerRadius = ctx.dp(12f)
                    setStroke(ctx.dp(1f).toInt(), 0xFFE5E7EB.toInt())
                }
                addView(ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_book)
                    setColorFilter(0xFF1E3A8A.toInt())
                    layoutParams = LinearLayout.LayoutParams(ctx.dp(72f).toInt(), ctx.dp(96f).toInt())
                })
                addView(TextView(ctx).apply {
                    id = android.R.id.text1
                    textSize = 16f
                    setTextColor(0xFF111827.toInt())
                    maxLines = 2
                    gravity = Gravity.CENTER
                })
                addView(TextView(ctx).apply {
                    id = android.R.id.text2
                    textSize = 12f
                    setTextColor(0xFF6B7280.toInt())
                    gravity = Gravity.CENTER
                })
            }
            val info = items[position]
            card.findViewById<TextView>(android.R.id.text1).text = info.title
            card.findViewById<TextView>(android.R.id.text2).text =
                "${info.pages} pág. · " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(info.modified))
            return card
        }
    }
}
