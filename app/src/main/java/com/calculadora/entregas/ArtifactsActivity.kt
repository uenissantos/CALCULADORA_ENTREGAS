package com.calculadora.entregas

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

// Lista de artefatos (print + leitura). Com EXTRA_PICK, serve para escolher a imagem do treino.
class ArtifactsActivity : Activity() {
    companion object {
        const val EXTRA_PICK = "pick"
        const val EXTRA_ID = "id"
    }

    private var items: List<ArtifactInfo> = emptyList()
    private var pick = false
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private val thumbs = LruCache<String, Bitmap>(40)
    private val loader = Executors.newFixedThreadPool(2)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pick = intent.getBooleanExtra(EXTRA_PICK, false)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(12), dp(32), dp(12), dp(12))

        val title = TextView(this)
        title.text = if (pick) "Escolha o print para treinar" else "Artefatos"
        title.textSize = 22f
        title.gravity = Gravity.CENTER
        root.addView(title)

        val sub = TextView(this)
        sub.text = if (pick) "Toque em um print para usar no treino." else
            "Cada item guarda o print (o que o app viu) e a leitura (o que o app leu). Toque para abrir."
        sub.textSize = 12f
        sub.setPadding(0, dp(4), 0, dp(8))
        root.addView(sub)

        if (!pick) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val shareAll = Button(this)
            shareAll.text = "Compartilhar tudo"
            shareAll.isAllCaps = false
            shareAll.setOnClickListener { ArtifactShare.shareAll(this) }
            row.addView(shareAll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val delAll = Button(this)
            delAll.text = "Apagar tudo"
            delAll.isAllCaps = false
            delAll.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("Apagar tudo")
                    .setMessage("Apagar todos os prints e leituras guardados?")
                    .setPositiveButton("Apagar") { _, _ ->
                        ArtifactStore.deleteAll(this)
                        thumbs.evictAll()
                        refresh()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            row.addView(delAll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(row)
        }

        empty = TextView(this)
        empty.text = "Nada guardado ainda.\nUse o ícone da câmera no botão flutuante, ou a calculadora quando não achar corrida."
        empty.gravity = Gravity.CENTER
        empty.setPadding(dp(16), dp(32), dp(16), dp(16))
        root.addView(empty)

        list = ListView(this)
        list.adapter = Adapter()
        list.setOnItemClickListener { _, _, position, _ -> open(items[position]) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = ArtifactStore.list(this)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun open(info: ArtifactInfo) {
        if (pick) {
            val data = Intent()
            data.putExtra(EXTRA_ID, info.id)
            setResult(RESULT_OK, data)
            finish()
        } else {
            val i = Intent(this, ArtifactDetailActivity::class.java)
            i.putExtra(EXTRA_ID, info.id)
            startActivity(i)
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val info = items[position]
            val row: LinearLayout
            val thumb: ImageView
            val line1: TextView
            val line2: TextView
            if (convertView == null) {
                row = LinearLayout(this@ArtifactsActivity)
                row.orientation = LinearLayout.HORIZONTAL
                row.setPadding(0, dp(6), 0, dp(6))
                thumb = ImageView(this@ArtifactsActivity)
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                thumb.setBackgroundColor(Color.rgb(40, 40, 40))
                row.addView(thumb, LinearLayout.LayoutParams(dp(64), dp(112)))
                val col = LinearLayout(this@ArtifactsActivity)
                col.orientation = LinearLayout.VERTICAL
                col.setPadding(dp(10), 0, 0, 0)
                line1 = TextView(this@ArtifactsActivity)
                line1.textSize = 15f
                line1.setTextColor(Color.BLACK)
                line2 = TextView(this@ArtifactsActivity)
                line2.textSize = 12f
                col.addView(line1)
                col.addView(line2)
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.tag = arrayOf<View>(thumb, line1, line2)
            } else {
                row = convertView as LinearLayout
                @Suppress("UNCHECKED_CAST")
                val parts = row.tag as Array<View>
                thumb = parts[0] as ImageView
                line1 = parts[1] as TextView
                line2 = parts[2] as TextView
            }

            line1.text = ArtifactStore.kindLabel(info.kind) + "  " + ArtifactStore.timeText(info.time)
            line1.setTextColor(
                when (info.kind) {
                    ArtifactStore.KIND_NOT_FOUND -> Color.rgb(198, 40, 40)
                    ArtifactStore.KIND_READ -> Color.rgb(46, 125, 50)
                    else -> Color.rgb(21, 101, 192)
                }
            )
            line2.text = info.status + (if (info.lineCount > 0) "\n" + info.lineCount + " textos lidos" else "")

            thumb.tag = info.id
            val cached = thumbs.get(info.id)
            if (cached != null) {
                thumb.setImageBitmap(cached)
            } else {
                thumb.setImageBitmap(null)
                loader.execute {
                    val b = ArtifactRender.decode(ArtifactStore.imageFile(this@ArtifactsActivity, info.id), 300)
                    if (b != null) {
                        thumbs.put(info.id, b)
                        runOnUiThread {
                            if (thumb.tag == info.id) thumb.setImageBitmap(b)
                        }
                    }
                }
            }
            return row
        }
    }
}

// Detalhe: o print e a leitura lado a lado, com caixas numeradas ligando um ao outro.
class ArtifactDetailActivity : Activity() {
    private var id = ""
    private var meta: org.json.JSONObject? = null
    private var original: Bitmap? = null
    private var commented: Bitmap? = null
    private var showingBoxes = false
    private lateinit var image: ImageView
    private lateinit var toggle: Button

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        id = intent.getStringExtra(ArtifactsActivity.EXTRA_ID) ?: ""
        meta = ArtifactStore.meta(this, id)
        original = ArtifactRender.decode(ArtifactStore.imageFile(this, id), 1800)
        val m = meta
        if (m == null || original == null) {
            Toast.makeText(this, "Artefato não encontrado", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(10), dp(32), dp(10), dp(10))

        val title = TextView(this)
        title.text = ArtifactStore.kindLabel(m.optString("kind")) + "  " + ArtifactStore.timeText(m.optLong("time")) + "\n" + m.optString("status")
        title.textSize = 14f
        title.setTextColor(Color.BLACK)
        root.addView(title)

        image = ImageView(this)
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        image.setBackgroundColor(Color.BLACK)
        image.setImageBitmap(original)
        root.addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.3f))

        toggle = Button(this)
        toggle.isAllCaps = false
        toggle.text = "Ver o que foi lido (caixas numeradas)"
        toggle.setOnClickListener { toggleBoxes() }
        root.addView(toggle)

        val report = TextView(this)
        report.text = ArtifactStore.reportText(m)
        report.textSize = 11f
        report.setTextIsSelectable(true)
        report.setPadding(dp(4), dp(4), dp(4), dp(4))
        val sv = ScrollView(this)
        sv.addView(report)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val share = Button(this)
        share.text = "Compartilhar"
        share.isAllCaps = false
        share.setOnClickListener { ArtifactShare.shareOne(this, id) }
        row.addView(share, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val del = Button(this)
        del.text = "Apagar"
        del.isAllCaps = false
        del.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Apagar")
                .setMessage("Apagar este print e esta leitura?")
                .setPositiveButton("Apagar") { _, _ ->
                    ArtifactStore.delete(this, id)
                    finish()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
        row.addView(del, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)

        setContentView(root)
    }

    private fun toggleBoxes() {
        val o = original ?: return
        val m = meta ?: return
        if (showingBoxes) {
            showingBoxes = false
            image.setImageBitmap(o)
            toggle.text = "Ver o que foi lido (caixas numeradas)"
            return
        }
        val ready = commented
        if (ready != null) {
            showingBoxes = true
            image.setImageBitmap(ready)
            toggle.text = "Ver o print original"
            return
        }
        toggle.text = "Montando..."
        Thread {
            val c = try {
                ArtifactRender.annotate(o, m)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (c == null) {
                    toggle.text = "Ver o que foi lido (caixas numeradas)"
                    Toast.makeText(this, "Não consegui montar a imagem", Toast.LENGTH_LONG).show()
                } else {
                    commented = c
                    showingBoxes = true
                    image.setImageBitmap(c)
                    toggle.text = "Ver o print original"
                }
            }
        }.start()
    }
}
