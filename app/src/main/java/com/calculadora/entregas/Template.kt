package com.calculadora.entregas

import android.content.Context
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject

// Cada campo que o usuário pode marcar na imagem de treino.
enum class Field(val label: String, val short: String) {
    VALOR("Valor da rota", "VALOR"),
    KM_UNICO("KM único", "KM ÚNICO"),
    KM_IDA("KM ida", "KM IDA"),
    KM_VOLTA("KM volta", "KM VOLTA"),
    MIN_UNICO("Tempo único", "MIN ÚNICO"),
    MIN_IDA("Tempo ida", "MIN IDA"),
    MIN_VOLTA("Tempo volta", "MIN VOLTA")
}

// Retângulos em proporção da tela (0..1), por isso valem em qualquer resolução.
data class Template(val name: String, val fields: Map<Field, RectF>)

// Palavra lida pelo OCR, com a posição em pixels.
data class OcrWord(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val line: Int
)

object TemplateStore {
    private val recent = LinkedHashSet<String>()

    private fun prefs(c: Context) = c.getSharedPreferences("templates", Context.MODE_PRIVATE)

    // Apps vistos pelo leitor, para sugerir o nome do modelo no treino.
    fun recordPackage(pkg: String) {
        if (pkg == "com.android.systemui") return
        recent.remove(pkg)
        recent.add(pkg)
        while (recent.size > 8) recent.remove(recent.first())
    }

    fun recentPackages(): List<String> = recent.toList().reversed()

    fun lastPackage(): String? = recent.lastOrNull()

    fun save(c: Context, t: Template) {
        val o = JSONObject()
        for ((f, r) in t.fields) {
            val a = JSONArray()
            a.put(r.left.toDouble())
            a.put(r.top.toDouble())
            a.put(r.right.toDouble())
            a.put(r.bottom.toDouble())
            o.put(f.name, a)
        }
        prefs(c).edit().putString(t.name, o.toString()).apply()
    }

    fun delete(c: Context, name: String) {
        prefs(c).edit().remove(name).apply()
    }

    fun get(c: Context, name: String): Template? = parse(name, prefs(c).getString(name, null))

    fun forPackage(c: Context, pkg: String?): Template? = if (pkg == null) null else get(c, pkg)

    fun all(c: Context): List<Template> {
        val out = ArrayList<Template>()
        for (entry in prefs(c).all) {
            val t = parse(entry.key, entry.value as? String)
            if (t != null) out.add(t)
        }
        return out
    }

    private fun parse(name: String, json: String?): Template? {
        if (json == null) return null
        return try {
            val o = JSONObject(json)
            val map = LinkedHashMap<Field, RectF>()
            for (f in Field.values()) {
                val a = o.optJSONArray(f.name) ?: continue
                if (a.length() == 4) {
                    map[f] = RectF(
                        a.getDouble(0).toFloat(),
                        a.getDouble(1).toFloat(),
                        a.getDouble(2).toFloat(),
                        a.getDouble(3).toFloat()
                    )
                }
            }
            if (map.isEmpty()) null else Template(name, map)
        } catch (_: Exception) {
            null
        }
    }
}

object TemplateReader {
    class Reading(val offer: OfferParser.ParsedOffer?, val raw: Map<Field, String>)

    private val moneyDecimal = Regex("""(\d{1,3}(?:\.\d{3})*,\d{2}|\d+,\d{2}|\d+\.\d{2})(?!\d)""")
    private val moneyInteger = Regex("""R\$\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val kmRegex = Regex("""(\d+(?:[.,]\d+)?)\s*(km|m)?(?![a-zA-Z])""", RegexOption.IGNORE_CASE)
    private val hoursRegex = Regex("""(\d+)\s*h""", RegexOption.IGNORE_CASE)
    private val minutesRegex = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
    private val firstInt = Regex("""\d+""")

    // Lê só o que está dentro de cada retângulo marcado; o que não foi marcado fica em branco.
    fun read(t: Template, words: List<OcrWord>, w: Int, h: Int): Reading {
        val raw = LinkedHashMap<Field, String>()
        for ((f, r) in t.fields) {
            val l = r.left * w
            val tp = r.top * h
            val rt = r.right * w
            val bt = r.bottom * h
            val inside = words.filter {
                val cx = (it.left + it.right) / 2f
                val cy = (it.top + it.bottom) / 2f
                cx >= l && cx <= rt && cy >= tp && cy <= bt
            }
            val text = inside
                .groupBy { it.line }
                .values
                .sortedBy { g -> g.minOf { it.top } }
                .joinToString(" ") { g -> g.sortedBy { it.left }.joinToString(" ") { it.text } }
            raw[f] = text
        }

        val valor = raw[Field.VALOR]?.let { parseMoney(it) }
        val kmUnico = raw[Field.KM_UNICO]?.let { parseKm(it) }
        val kmIda = raw[Field.KM_IDA]?.let { parseKm(it) }
        val kmVolta = raw[Field.KM_VOLTA]?.let { parseKm(it) }
        val minUnico = raw[Field.MIN_UNICO]?.let { parseMinutes(it) }
        val minIda = raw[Field.MIN_IDA]?.let { parseMinutes(it) }
        val minVolta = raw[Field.MIN_VOLTA]?.let { parseMinutes(it) }

        // "Único" já vem somado no card; sem ele, soma ida + volta.
        val km = kmUnico ?: ((kmIda ?: 0.0) + (kmVolta ?: 0.0))
        val minutes = minUnico ?: ((minIda ?: 0.0) + (minVolta ?: 0.0))

        val offer = if (valor == null || valor <= 0.0 || km <= 0.0) {
            null
        } else {
            OfferParser.ParsedOffer(valor, km, minutes, 0, 0)
        }
        return Reading(offer, raw)
    }

    fun parseMoney(text: String): Double? {
        val s = text.replace('\u00A0', ' ')
        val m = moneyDecimal.find(s)
        if (m != null) {
            val g = m.groupValues[1]
            val v = if (g.contains(',')) g.replace(".", "").replace(',', '.') else g
            return v.toDoubleOrNull()
        }
        return moneyInteger.find(s)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    fun parseKm(text: String): Double? {
        val s = text.replace('\u00A0', ' ')
        val m = kmRegex.find(s) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return if (m.groupValues[2].equals("m", ignoreCase = true)) v / 1000.0 else v
    }

    fun parseMinutes(text: String): Double? {
        val s = text.replace('\u00A0', ' ')
        val hh = hoursRegex.find(s)?.groupValues?.get(1)?.toDoubleOrNull()
        val mm = minutesRegex.find(s)?.groupValues?.get(1)?.toDoubleOrNull()
        if (hh != null || mm != null) return (hh ?: 0.0) * 60.0 + (mm ?: 0.0)
        return firstInt.find(s)?.value?.toDoubleOrNull()
    }
}
