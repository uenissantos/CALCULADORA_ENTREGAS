package com.calculadora.entregas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// De onde vêm os textos de um modelo de texto.
object TextSrc {
    const val ACC = "TELA" // textos que o app de corrida entrega pela acessibilidade (funciona mesmo sem print)
    const val OCR = "FOTO" // textos lidos do print por OCR

    fun label(src: String): String = if (src == ACC) "texto da tela" else "texto da foto"
}

// Regra de um campo: o formato do trecho lido ("r$#", "#km", "#min"), qual deles pegar (nth)
// e se ele fica depois ou antes do valor. Não depende da posição na tela.
data class TextRule(val shape: String, val nth: Int, val after: Boolean)

data class TextModel(
    val name: String,
    val pkg: String?,
    val src: String,
    val rules: Map<Field, TextRule>,
    val anchors: List<String>
)

object TextStore {
    private fun prefs(c: Context) = c.getSharedPreferences("textmodels", Context.MODE_PRIVATE)

    fun count(c: Context): Int = prefs(c).all.size

    fun exists(c: Context, name: String): Boolean = prefs(c).contains(name)

    fun nextName(c: Context): String {
        var n = 1
        while (prefs(c).contains("Texto $n")) n++
        return "Texto $n"
    }

    fun toJson(m: TextModel): JSONObject {
        val o = JSONObject()
        o.put("src", m.src)
        if (m.pkg != null) o.put("pkg", m.pkg)
        val r = JSONObject()
        for ((f, rule) in m.rules) {
            val x = JSONObject()
            x.put("s", rule.shape)
            x.put("n", rule.nth)
            x.put("a", rule.after)
            r.put(f.name, x)
        }
        o.put("rules", r)
        val an = JSONArray()
        for (a in m.anchors) an.put(a)
        o.put("anchors", an)
        return o
    }

    fun save(c: Context, m: TextModel) {
        prefs(c).edit().putString(m.name, toJson(m).toString()).apply()
        TemplateStore.version++
    }

    fun delete(c: Context, name: String) {
        prefs(c).edit().remove(name).apply()
        TemplateStore.version++
    }

    fun get(c: Context, name: String): TextModel? = parse(name, prefs(c).getString(name, null))

    fun all(c: Context): List<TextModel> {
        val out = ArrayList<TextModel>()
        for (entry in prefs(c).all) {
            val m = parse(entry.key, entry.value as? String)
            if (m != null) out.add(m)
        }
        out.sortBy { it.name }
        return out
    }

    // Usado pela restauração do backup.
    fun saveRaw(c: Context, name: String, modelo: JSONObject) {
        prefs(c).edit().putString(name, modelo.toString()).apply()
    }

    private fun parse(name: String, json: String?): TextModel? {
        if (json == null) return null
        return try {
            val o = JSONObject(json)
            val rules = LinkedHashMap<Field, TextRule>()
            val r = o.optJSONObject("rules") ?: return null
            for (f in Field.values()) {
                val x = r.optJSONObject(f.name) ?: continue
                rules[f] = TextRule(x.getString("s"), x.getInt("n"), x.optBoolean("a", true))
            }
            val anchors = ArrayList<String>()
            val an = o.optJSONArray("anchors")
            if (an != null) for (i in 0 until an.length()) anchors.add(an.getString(i))
            val pkg: String? = o.optString("pkg", "").ifEmpty { null }
            if (!rules.containsKey(Field.VALOR)) null else TextModel(name, pkg, o.optString("src", TextSrc.ACC), rules, anchors)
        } catch (_: Exception) {
            null
        }
    }
}

object TextReader {
    class Snip(val key: Int, val text: String, val shape: String)
    class Reading(val offer: OfferParser.ParsedOffer?, val raw: Map<Field, String>)

    // Valor: com R$ (ou com centavos). Ignora "R$1,58/km".
    private val moneyRx = Regex(
        """R\$\s*\d{1,3}(?:\.\d{3})*,\d{2}(?!\d)(?!\s*/)|R\$\s*\d+\.\d{2}(?!\d)(?!\s*/)|(?<![\d.,])\d{1,3}(?:\.\d{3})*,\d{2}(?!\d)(?!\s*/)""",
        RegexOption.IGNORE_CASE
    )
    private val kmRx = Regex("""(\d+(?:[.,]\d+)?)\s*(km|m)(?![a-zA-Z])""", RegexOption.IGNORE_CASE)
    private val timeRx = Regex(
        """\d+\s*h(?![a-zA-Z])\s*\d+\s*min|\d+\s*min|\d+\s*h(?![a-zA-Z])""",
        RegexOption.IGNORE_CASE
    )
    private val numRx = Regex("""\d+(?:[.,]\d+)*""")

    private fun rxFor(f: Field): Regex = when (f) {
        Field.VALOR -> moneyRx
        Field.KM_UNICO, Field.KM_IDA, Field.KM_VOLTA -> kmRx
        else -> timeRx
    }

    // "R$7,21" -> "r$#", "6,1 km" -> "#km", "16 min" -> "#min".
    fun shapeOf(s: String): String =
        numRx.replace(s.lowercase().replace('\u00A0', ' ').filter { !it.isWhitespace() }, "#")

    // Trechos de um texto que servem para o campo, com a posição dentro do texto.
    fun snippets(f: Field, text: String): List<Pair<Int, String>> =
        rxFor(f).findAll(text.replace('\u00A0', ' ')).map { it.range.first to it.value }.toList()

    private fun key(index: Int, offset: Int): Int = index * 1000 + minOf(offset, 999)

    private fun allSnips(f: Field, items: List<OfferParser.TextItem>): List<Snip> {
        val out = ArrayList<Snip>()
        for ((i, item) in items.withIndex()) {
            for ((off, s) in snippets(f, item.text)) out.add(Snip(key(i, off), s, shapeOf(s)))
        }
        return out
    }

    // Monta as regras a partir do que foi marcado: campo -> número do texto na lista.
    fun train(
        name: String,
        pkg: String?,
        src: String,
        items: List<OfferParser.TextItem>,
        sel: Map<Field, Int>,
        anchorLines: Collection<Int>,
        occ: Map<Field, Int> = emptyMap()
    ): Pair<TextModel?, String?> {
        val vIdx = sel[Field.VALOR] ?: return Pair(null, "Marque o valor da rota")
        val picked = HashMap<Field, Snip>()
        for ((f, idx) in sel) {
            // Vários trechos iguais na mesma linha (ex.: coleta e entrega juntas): occ diz qual.
            val first = snippets(f, items[idx].text).getOrNull(occ[f] ?: 0)
                ?: return Pair(null, "O texto marcado como ${f.label} não tem esse dado")
            picked[f] = Snip(key(idx, first.first), first.second, shapeOf(first.second))
        }
        val v = picked[Field.VALOR] ?: return Pair(null, "Marque o valor da rota")
        val rules = LinkedHashMap<Field, TextRule>()
        val vAll = allSnips(Field.VALOR, items).filter { it.shape == v.shape }
        rules[Field.VALOR] = TextRule(v.shape, maxOf(0, vAll.indexOfFirst { it.key == v.key }), true)
        for ((f, p) in picked) {
            if (f == Field.VALOR) continue
            val after = p.key > v.key
            val all = allSnips(f, items).filter { it.shape == p.shape }
            val cands = if (after) all.filter { it.key > v.key } else all.filter { it.key < v.key }.asReversed()
            rules[f] = TextRule(p.shape, maxOf(0, cands.indexOfFirst { it.key == p.key }), after)
        }
        val anchors = ArrayList<String>()
        for (i in anchorLines) {
            val a = shapeOf(items[i].text)
            if (a.replace("#", "").isNotEmpty() && !anchors.contains(a)) anchors.add(a)
        }
        return Pair(TextModel(name, pkg, src, rules, anchors), null)
    }

    fun read(m: TextModel, items: List<OfferParser.TextItem>): Reading {
        val sorted = items.sortedBy { it.top }
        val raw = LinkedHashMap<Field, String>()
        for (f in m.rules.keys) raw[f] = ""
        // Âncoras: textos fixos que precisam estar na tela, senão não é a tela treinada.
        val shapes = sorted.map { shapeOf(it.text) }
        for (a in m.anchors) if (shapes.none { it.contains(a) }) return Reading(null, raw)
        val vr = m.rules[Field.VALOR] ?: return Reading(null, raw)
        val vs = allSnips(Field.VALOR, sorted).filter { it.shape == vr.shape }.getOrNull(vr.nth)
            ?: return Reading(null, raw)
        raw[Field.VALOR] = vs.text
        for ((f, rule) in m.rules) {
            if (f == Field.VALOR) continue
            val all = allSnips(f, sorted).filter { it.shape == rule.shape }
            val cands = if (rule.after) all.filter { it.key > vs.key } else all.filter { it.key < vs.key }.asReversed()
            val s = cands.getOrNull(rule.nth)
            if (s != null) raw[f] = s.text
        }

        val valor = TemplateReader.parseMoney(raw[Field.VALOR] ?: "")
        fun kmOf(f: Field): Double? = raw[f]?.takeIf { it.isNotBlank() }?.let { TemplateReader.parseKm(it) }
        fun minOfField(f: Field): Double? = raw[f]?.takeIf { it.isNotBlank() }?.let { TemplateReader.parseMinutes(it) }
        val kmTotal = kmOf(Field.KM_UNICO) ?: ((kmOf(Field.KM_IDA) ?: 0.0) + (kmOf(Field.KM_VOLTA) ?: 0.0))
        val minutes = minOfField(Field.MIN_UNICO) ?: ((minOfField(Field.MIN_IDA) ?: 0.0) + (minOfField(Field.MIN_VOLTA) ?: 0.0))
        val offer = if (valor == null || valor <= 0.0 || valor > 5000.0 || kmTotal <= 0.0 || kmTotal > 400.0) {
            null
        } else {
            OfferParser.ParsedOffer(valor, kmTotal, minutes, 0, 0)
        }
        return Reading(offer, raw)
    }
}
