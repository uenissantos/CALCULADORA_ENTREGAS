package com.calculadora.entregas

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

// Treino pelo texto: mostra os textos que o app leu num artefato. Você toca em um texto e diz
// se é Valor, KM ou Tempo. O modelo guarda o formato (não a posição), então vale mesmo se o card mudar de lugar.
class TextTrainActivity : Activity() {
    private var meta: org.json.JSONObject? = null
    private var pkg: String? = null
    private var accItems: List<OfferParser.TextItem> = emptyList()
    private var ocrItems: List<OfferParser.TextItem> = emptyList()
    private var src = TextSrc.ACC
    private var items: List<OfferParser.TextItem> = emptyList()
    private val sel = LinkedHashMap<Field, Int>()
    private val occ = LinkedHashMap<Field, Int>()
    private val anchors = LinkedHashSet<Int>()
    private lateinit var list: ListView
    private lateinit var srcBtn: Button

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(ArtifactsActivity.EXTRA_ID) ?: ""
        val m = ArtifactStore.meta(this, id)
        if (m == null) {
            toast("Artefato não encontrado")
            finish()
            return
        }
        meta = m
        pkg = m.optString("pkg").ifEmpty { null }
        accItems = ArtifactStore.textItems(m, TextSrc.ACC)
        ocrItems = ArtifactStore.textItems(m, TextSrc.OCR)
        if (accItems.isEmpty() && ocrItems.isEmpty()) {
            toast("Esse artefato não tem nenhum texto para treinar")
            finish()
            return
        }
        src = if (accItems.isNotEmpty()) TextSrc.ACC else TextSrc.OCR
        items = if (src == TextSrc.ACC) accItems else ocrItems

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(10), dp(32), dp(10), dp(10))

        val title = TextView(this)
        title.text = "Treinar pelo texto"
        title.textSize = 20f
        title.gravity = Gravity.CENTER
        title.setTextColor(Color.BLACK)
        root.addView(title)

        val help = TextView(this)
        help.text = "Toque em um texto e diga o que ele é (Valor, KM, Tempo). " +
            "Âncora = texto fixo do card (ex.: o nome do botão); se marcar, o card só vale quando a âncora estiver na tela."
        help.textSize = 12f
        help.setPadding(0, dp(4), 0, dp(6))
        root.addView(help)

        srcBtn = Button(this)
        srcBtn.isAllCaps = false
        srcBtn.setOnClickListener { switchSource() }
        root.addView(srcBtn)
        updateSourceButton()

        list = ListView(this)
        list.adapter = Adapter()
        list.setOnItemClickListener { _, _, position, _ -> choose(position) }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        fun btn(label: String, onClick: () -> Unit) {
            val b = Button(this)
            b.text = label
            b.isAllCaps = false
            b.setOnClickListener { onClick() }
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        btn("Limpar") { clearAll() }
        btn("Salvos") { showSaved() }
        btn("✓ Conferir") { confirm() }
        root.addView(row)

        setContentView(root)
    }

    private fun updateSourceButton() {
        val both = accItems.isNotEmpty() && ocrItems.isNotEmpty()
        val name = if (src == TextSrc.ACC) "Texto da tela (acessibilidade)" else "Texto da foto (OCR)"
        srcBtn.text = "Fonte: $name — ${items.size} textos" + if (both) "  (toque para trocar)" else ""
        srcBtn.isEnabled = both
    }

    private fun switchSource() {
        src = if (src == TextSrc.ACC) TextSrc.OCR else TextSrc.ACC
        items = if (src == TextSrc.ACC) accItems else ocrItems
        sel.clear()
        occ.clear()
        anchors.clear()
        updateSourceButton()
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun clearAll() {
        sel.clear()
        occ.clear()
        anchors.clear()
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun tagsOf(index: Int): String {
        val tags = ArrayList<String>()
        for ((f, i) in sel) if (i == index) tags.add(f.short)
        if (anchors.contains(index)) tags.add("ÂNCORA")
        return tags.joinToString(" + ")
    }

    // Se a linha tem mais de um trecho do mesmo tipo (ex.: dois KM), pergunta qual.
    private fun assignAsk(f: Field, index: Int) {
        val sn = TextReader.snippets(f, items[index].text)
        if (sn.size <= 1) {
            assign(f, index, 0)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(f.label + ": qual deles?")
            .setItems(sn.map { it.second }.toTypedArray()) { _, which -> assign(f, index, which) }
            .show()
    }

    // Tempo e KM na mesma linha: marca os dois de uma vez. leg 0 = primeiro trecho, 1 = segundo.
    private fun assignPair(kmF: Field, minF: Field, index: Int, leg: Int) {
        val k = TextReader.snippets(kmF, items[index].text).size
        val t = TextReader.snippets(minF, items[index].text).size
        if (k == 0 || t == 0) {
            toast("Esse texto não tem tempo e KM juntos")
            return
        }
        assign(kmF, index, if (k > 1) leg else 0)
        assign(minF, index, if (t > 1) leg else 0)
    }

    private fun assign(f: Field, index: Int, o: Int) {
        if (TextReader.snippets(f, items[index].text).isEmpty()) {
            val what = when (f) {
                Field.VALOR -> "um valor (ex.: R\$7,21)"
                Field.KM_UNICO, Field.KM_IDA, Field.KM_VOLTA -> "um KM (ex.: 6,1 km)"
                else -> "um tempo (ex.: 16 min)"
            }
            toast("Esse texto não tem $what")
            return
        }
        sel[f] = index
        occ[f] = o
        (list.adapter as BaseAdapter).notifyDataSetChanged()
    }

    private fun choose(index: Int) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        fun add(label: String, action: () -> Unit) {
            labels.add(label)
            actions.add(action)
        }
        add("Valor") { assign(Field.VALOR, index, 0) }
        add("Tempo + KM únicos (mesma linha)") { assignPair(Field.KM_UNICO, Field.MIN_UNICO, index, 0) }
        add("Tempo + KM da coleta (mesma linha)") { assignPair(Field.KM_IDA, Field.MIN_IDA, index, 0) }
        add("Tempo + KM da entrega (mesma linha)") { assignPair(Field.KM_VOLTA, Field.MIN_VOLTA, index, 1) }
        add("KM único") { assignAsk(Field.KM_UNICO, index) }
        add("KM coleta") { assignAsk(Field.KM_IDA, index) }
        add("KM entrega") { assignAsk(Field.KM_VOLTA, index) }
        add("Tempo único") { assignAsk(Field.MIN_UNICO, index) }
        add("Tempo coleta") { assignAsk(Field.MIN_IDA, index) }
        add("Tempo entrega") { assignAsk(Field.MIN_VOLTA, index) }
        add(if (anchors.contains(index)) "Tirar âncora" else "Âncora (texto fixo)") {
            if (!anchors.remove(index)) anchors.add(index)
            (list.adapter as BaseAdapter).notifyDataSetChanged()
        }
        if (tagsOf(index).isNotEmpty()) {
            add("Limpar marcações deste texto") {
                val gone = sel.entries.filter { it.value == index }.map { it.key }
                for (f in gone) {
                    sel.remove(f)
                    occ.remove(f)
                }
                anchors.remove(index)
                (list.adapter as BaseAdapter).notifyDataSetChanged()
            }
        }
        AlertDialog.Builder(this)
            .setTitle(items[index].text.replace('\n', ' ').take(60))
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun confirm() {
        if (!sel.containsKey(Field.VALOR)) {
            toast("Marque o valor da rota")
            return
        }
        if (!sel.containsKey(Field.KM_UNICO) && !sel.containsKey(Field.KM_IDA) && !sel.containsKey(Field.KM_VOLTA)) {
            toast("Marque o KM (único, ou coleta e entrega)")
            return
        }
        val (model, error) = TextReader.train("teste", pkg, src, items, LinkedHashMap(sel), LinkedHashSet(anchors), LinkedHashMap(occ))
        if (model == null) {
            toast(error ?: "Não consegui montar o modelo")
            return
        }
        val reading = TextReader.read(model, items)
        AlertDialog.Builder(this)
            .setTitle("O que o app leu")
            .setMessage("Vale para: " + (pkg ?: "qualquer app") + "\nFonte: " + TextSrc.label(src) + "\n\n" + report(model, reading))
            .setPositiveButton("Salvar") { _, _ -> askName(model) }
            .setNegativeButton("Voltar", null)
            .show()
    }

    private fun report(model: TextModel, reading: TextReader.Reading): String {
        val sb = StringBuilder()
        for (f in Field.values()) {
            val shown = if (!model.rules.containsKey(f)) {
                "(em branco)"
            } else {
                val t = reading.raw[f] ?: ""
                if (t.isBlank()) "(nada lido)" else t
            }
            sb.append(f.label).append(": ").append(shown).append('\n')
        }
        if (model.anchors.isNotEmpty()) sb.append("Âncoras: ").append(model.anchors.size).append('\n')
        val o = reading.offer
        if (o == null) {
            sb.append("\nNão deu para calcular: faltou valor ou KM legível.")
        } else {
            sb.append("\nValor ").append(OfferCalculator.money(o.amount))
                .append(" | ").append(OfferCalculator.decimal(o.km)).append(" km")
                .append(" | ").append(o.minutes.toInt()).append(" min")
            val calc = OfferCalculator.calculate(o.amount, o.km, o.minutes, AppConfig.load(this))
            if (calc != null) {
                sb.append("\nKM ").append(OfferCalculator.money(calc.perKm))
                    .append(" | Hora ").append(calc.perHour?.let { OfferCalculator.money(it) } ?: "—")
                    .append(" | Lucro ").append(OfferCalculator.money(calc.profit))
            }
        }
        return sb.toString()
    }

    private fun askName(model: TextModel) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setSingleLine()
        input.setText(TextStore.nextName(this))
        input.setSelection(input.text.length)
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("Nome do modelo de texto")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                val name = input.text.toString().trim().take(40)
                if (name.isEmpty()) {
                    toast("Digite um nome")
                } else if (TextStore.exists(this, name)) {
                    AlertDialog.Builder(this)
                        .setTitle("Já existe")
                        .setMessage("Já existe um modelo de texto chamado \"$name\". Substituir?")
                        .setPositiveButton("Substituir") { _, _ -> persist(model, name) }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    persist(model, name)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun persist(model: TextModel, name: String) {
        TextStore.save(this, model.copy(name = name))
        toast("Modelo de texto salvo: $name")
        finish()
    }

    private fun showSaved() {
        val saved = TextStore.all(this)
        if (saved.isEmpty()) {
            toast("Nenhum modelo de texto salvo ainda")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Modelos de texto (toque para apagar)")
            .setItems(saved.map { it.name + " — " + TextSrc.label(it.src) + " — " + (it.pkg ?: "qualquer app") }.toTypedArray()) { _, which ->
                val name = saved[which].name
                AlertDialog.Builder(this)
                    .setTitle("Apagar")
                    .setMessage("Apagar o modelo de texto \"$name\"?")
                    .setPositiveButton("Apagar") { _, _ ->
                        TextStore.delete(this, name)
                        toast("Modelo apagado: $name")
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .show()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: TextView(this@TextTrainActivity).also {
                it.textSize = 14f
                it.setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            val tags = tagsOf(position)
            val text = (position + 1).toString() + ".  " + items[position].text.replace('\n', ' ')
            tv.text = if (tags.isEmpty()) text else "$text\n→ $tags"
            tv.setTextColor(Color.BLACK)
            tv.setBackgroundColor(if (tags.isEmpty()) Color.TRANSPARENT else Color.argb(70, 0, 170, 90))
            return tv
        }
    }
}
