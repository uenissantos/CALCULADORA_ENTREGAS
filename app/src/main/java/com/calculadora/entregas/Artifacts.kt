package com.calculadora.entregas

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ArtifactInfo(
    val id: String,
    val time: Long,
    val kind: String,
    val status: String,
    val lineCount: Int
)

// Artefato = o print (o que o app viu) + a leitura (o que o app leu), ligados pelo mesmo id.
// Arquivos: <id>.jpg (print) e <id>.json (leitura, com a posição de cada texto no print).
object ArtifactStore {
    const val KIND_NOT_FOUND = "NAO_ACHOU"
    const val KIND_READ = "LIDO"
    const val KIND_PRINT = "PRINT"
    private const val MAX_ITEMS = 150
    private val io = Executors.newSingleThreadExecutor()

    private class Line(val text: String, val l: Int, val t: Int, val r: Int, val b: Int)

    private fun prefs(c: Context) = c.getSharedPreferences("artefatos", Context.MODE_PRIVATE)

    fun keepSuccess(c: Context): Boolean = prefs(c).getBoolean("guardarAcertos", false)

    fun setKeepSuccess(c: Context, value: Boolean) {
        prefs(c).edit().putBoolean("guardarAcertos", value).apply()
    }

    fun dir(c: Context): File {
        val d = File(c.filesDir, "artefatos")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun imageFile(c: Context, id: String): File = File(dir(c), "$id.jpg")

    private fun metaFile(c: Context, id: String): File = File(dir(c), "$id.json")

    fun kindLabel(kind: String): String = when (kind) {
        KIND_NOT_FOUND -> "Não achou corrida"
        KIND_READ -> "Leitura"
        KIND_PRINT -> "Print"
        else -> kind
    }

    private fun groupLines(words: List<OcrWord>): List<Line> {
        val out = ArrayList<Line>()
        for (g in words.groupBy { it.line }.values) {
            val s = g.sortedBy { it.left }
            out.add(
                Line(
                    s.joinToString(" ") { it.text },
                    s.minOf { it.left },
                    s.minOf { it.top },
                    s.maxOf { it.right },
                    s.maxOf { it.bottom }
                )
            )
        }
        out.sortBy { it.t }
        return out
    }

    // Salva o print e a leitura juntos. O JPEG é comprimido na hora; a gravação em arquivo vai em segundo plano.
    fun save(c: Context, bmp: Bitmap, kind: String, status: String, words: List<OcrWord>?, models: JSONArray?, pkg: String? = null): String {
        val id = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        val bytes = bos.toByteArray()

        val meta = JSONObject()
        meta.put("id", id)
        meta.put("time", System.currentTimeMillis())
        meta.put("kind", kind)
        meta.put("status", status)
        meta.put("pkg", pkg ?: "")
        meta.put("w", bmp.width)
        meta.put("h", bmp.height)
        val lines = JSONArray()
        if (words != null) {
            var n = 1
            for (ln in groupLines(words)) {
                val o = JSONObject()
                o.put("n", n)
                o.put("text", ln.text)
                o.put("l", ln.l)
                o.put("t", ln.t)
                o.put("r", ln.r)
                o.put("b", ln.b)
                lines.put(o)
                n++
            }
        }
        meta.put("lines", lines)
        if (models != null) meta.put("modelos", models)

        val app = c.applicationContext
        io.execute {
            try {
                imageFile(app, id).writeBytes(bytes)
                metaFile(app, id).writeText(meta.toString())
                prune(app)
            } catch (_: Exception) {
            }
        }
        return id
    }

    private fun prune(c: Context) {
        val files = dir(c).listFiles()?.filter { it.name.endsWith(".json") } ?: return
        val sorted = files.sortedByDescending { it.name }
        for (i in MAX_ITEMS until sorted.size) {
            val id = sorted[i].name.removeSuffix(".json")
            delete(c, id)
        }
    }

    fun list(c: Context): List<ArtifactInfo> {
        val files = dir(c).listFiles()?.filter { it.name.endsWith(".json") } ?: return emptyList()
        val out = ArrayList<ArtifactInfo>()
        for (f in files.sortedByDescending { it.name }) {
            try {
                val o = JSONObject(f.readText())
                out.add(
                    ArtifactInfo(
                        o.getString("id"),
                        o.optLong("time"),
                        o.optString("kind"),
                        o.optString("status"),
                        o.optJSONArray("lines")?.length() ?: 0
                    )
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun meta(c: Context, id: String): JSONObject? {
        return try {
            JSONObject(metaFile(c, id).readText())
        } catch (_: Exception) {
            null
        }
    }

    fun delete(c: Context, id: String) {
        imageFile(c, id).delete()
        metaFile(c, id).delete()
    }

    fun deleteAll(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
    }

    fun timeText(ms: Long): String = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("pt", "BR")).format(Date(ms))

    // Texto da leitura, ligado ao print pelo id e pelos números das caixas.
    fun reportText(meta: JSONObject): String {
        val sb = StringBuilder()
        sb.append("ARTEFATO ").append(meta.optString("id")).append('\n')
        sb.append("Hora: ").append(timeText(meta.optLong("time"))).append('\n')
        sb.append("Tipo: ").append(kindLabel(meta.optString("kind"))).append('\n')
        sb.append("Resultado: ").append(meta.optString("status")).append('\n')
        val pkgName = meta.optString("pkg")
        if (pkgName.isNotEmpty()) sb.append("App na tela: ").append(pkgName).append('\n')
        sb.append("Tamanho do print: ").append(meta.optInt("w")).append("x").append(meta.optInt("h")).append('\n')

        val models = meta.optJSONArray("modelos")
        if (models != null && models.length() > 0) {
            sb.append("\nMODELOS TREINADOS (o que cada um leu):\n")
            for (i in 0 until models.length()) {
                val m = models.getJSONObject(i)
                sb.append("- ").append(m.optString("nome")).append(if (m.optBoolean("ok")) " (leu valor e KM)" else " (não conseguiu)").append('\n')
                val campos = m.optJSONObject("campos")
                if (campos != null) {
                    val keys = campos.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = campos.optString(k)
                        sb.append("    ").append(k).append(": ").append(if (v.isBlank()) "(nada lido)" else v).append('\n')
                    }
                }
            }
        }

        val lines = meta.optJSONArray("lines")
        sb.append("\nTEXTOS LIDOS NO PRINT (número = caixa na imagem comentada):\n")
        if (lines == null || lines.length() == 0) {
            sb.append("(nenhum texto lido)\n")
        } else {
            for (i in 0 until lines.length()) {
                val o = lines.getJSONObject(i)
                sb.append('[').append(o.optInt("n")).append("] (")
                    .append(o.optInt("l")).append(',').append(o.optInt("t")).append(")-(")
                    .append(o.optInt("r")).append(',').append(o.optInt("b")).append("): ")
                    .append(o.optString("text").replace('\n', ' ')).append('\n')
            }
        }
        return sb.toString()
    }
}

object ArtifactRender {
    // Desenha no print: faixa com o resultado, caixas numeradas em cada texto lido,
    // regiões dos modelos e, embaixo, a lista do que foi lido.
    fun annotate(src: Bitmap, meta: JSONObject): Bitmap {
        val w = src.width.toFloat()
        val sc = w / maxOf(1, meta.optInt("w", src.width)).toFloat()
        val lines = meta.optJSONArray("lines") ?: JSONArray()

        val listPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        listPaint.color = Color.WHITE
        listPaint.textSize = w / 40f
        val lineH = listPaint.textSize * 1.35f
        val maxList = minOf(lines.length(), 45)
        val pad = w / 40f
        val panelH = if (maxList == 0) 0f else pad * 2 + lineH * (maxList + 1)

        val out = Bitmap.createBitmap(src.width, (src.height + panelH).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        c.drawBitmap(src, 0f, 0f, null)

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = maxOf(2f, w / 320f)
        stroke.color = Color.rgb(255, 214, 0)
        val tagBg = Paint(Paint.ANTI_ALIAS_FLAG)
        tagBg.color = Color.rgb(255, 214, 0)
        val tagText = Paint(Paint.ANTI_ALIAS_FLAG)
        tagText.color = Color.BLACK
        tagText.textSize = w / 42f
        tagText.typeface = Typeface.DEFAULT_BOLD

        // Regiões dos modelos treinados (onde cada um foi procurar).
        val models = meta.optJSONArray("modelos")
        if (models != null) {
            val regionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            regionPaint.style = Paint.Style.STROKE
            regionPaint.strokeWidth = maxOf(2f, w / 400f)
            regionPaint.color = Color.rgb(0, 229, 255)
            val regionText = Paint(Paint.ANTI_ALIAS_FLAG)
            regionText.color = Color.rgb(0, 229, 255)
            regionText.textSize = w / 52f
            regionText.typeface = Typeface.DEFAULT_BOLD
            for (i in 0 until models.length()) {
                val reg = models.getJSONObject(i).optJSONObject("regioes") ?: continue
                val keys = reg.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val a = reg.optJSONArray(k) ?: continue
                    if (a.length() != 4) continue
                    val l = (a.getDouble(0) * src.width).toFloat()
                    val t = (a.getDouble(1) * src.height).toFloat()
                    val r = (a.getDouble(2) * src.width).toFloat()
                    val b = (a.getDouble(3) * src.height).toFloat()
                    c.drawRect(l, t, r, b, regionPaint)
                    c.drawText(k, l, b + regionText.textSize, regionText)
                }
            }
        }

        for (i in 0 until lines.length()) {
            val o = lines.getJSONObject(i)
            val l = o.optInt("l") * sc
            val t = o.optInt("t") * sc
            val r = o.optInt("r") * sc
            val b = o.optInt("b") * sc
            c.drawRect(l, t, r, b, stroke)
            val label = o.optInt("n").toString()
            val tw = tagText.measureText(label) + w / 120f
            c.drawRect(l, t - tagText.textSize, l + tw, t, tagBg)
            c.drawText(label, l + w / 240f, t - tagText.textSize * 0.2f, tagText)
        }

        // Faixa de resultado no topo.
        val kind = meta.optString("kind")
        val bannerColor = when (kind) {
            ArtifactStore.KIND_NOT_FOUND -> Color.rgb(211, 47, 47)
            ArtifactStore.KIND_READ -> Color.rgb(46, 125, 50)
            else -> Color.rgb(21, 101, 192)
        }
        val bannerH = w / 7f
        val bg = Paint()
        bg.color = bannerColor
        bg.alpha = 230
        c.drawRect(0f, 0f, w, bannerH, bg)
        val t1 = Paint(Paint.ANTI_ALIAS_FLAG)
        t1.color = Color.WHITE
        t1.typeface = Typeface.DEFAULT_BOLD
        t1.textSize = w / 24f
        c.drawText(
            ArtifactStore.kindLabel(kind).uppercase(Locale.getDefault()) + "  " + ArtifactStore.timeText(meta.optLong("time")),
            w / 40f,
            bannerH * 0.42f,
            t1
        )
        val t2 = Paint(Paint.ANTI_ALIAS_FLAG)
        t2.color = Color.WHITE
        t2.textSize = w / 32f
        val status = meta.optString("status")
        val n = t2.breakText(status, true, w - w / 20f, null)
        c.drawText(status.substring(0, n), w / 40f, bannerH * 0.82f, t2)

        // Lista do que foi lido, escrita embaixo do print.
        if (maxList > 0) {
            var y = src.height + pad + lineH
            c.drawText("O QUE FOI LIDO (${lines.length()} textos)", pad, y, listPaint)
            for (i in 0 until maxList) {
                val o = lines.getJSONObject(i)
                y += lineH
                val text = "[" + o.optInt("n") + "] " + o.optString("text").replace('\n', ' ')
                val cut = listPaint.breakText(text, true, w - pad * 2, null)
                c.drawText(text.substring(0, cut), pad, y, listPaint)
            }
        }
        return out
    }

    fun decode(file: File, maxSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outHeight / sample > maxSide || bounds.outWidth / sample > maxSide) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }
}

object ArtifactShare {
    private fun shareDir(a: Activity): File {
        val d = File(a.cacheDir, "share")
        if (!d.exists()) d.mkdirs()
        d.listFiles()?.forEach { it.delete() }
        return d
    }

    private fun uri(a: Activity, f: File): Uri =
        FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)

    private fun launch(a: Activity, intent: Intent, uris: List<Uri>) {
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = ClipData.newRawUri("artefato", uris[0])
        for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
        intent.clipData = clip
        a.startActivity(Intent.createChooser(intent, "Compartilhar artefato"))
    }

    // Um artefato: imagem comentada + print original + texto da leitura.
    fun shareOne(a: Activity, id: String) {
        Toast.makeText(a, "Preparando...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val meta = ArtifactStore.meta(a, id)
                val orig = ArtifactRender.decode(ArtifactStore.imageFile(a, id), 4000)
                if (meta == null || orig == null) {
                    a.runOnUiThread { Toast.makeText(a, "Artefato não encontrado", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                val dir = shareDir(a)
                val commented = ArtifactRender.annotate(orig, meta)
                val fCom = File(dir, "artefato_${id}_comentado.jpg")
                fCom.outputStream().use { commented.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                commented.recycle()
                orig.recycle()
                val fOrig = File(dir, "artefato_$id.jpg")
                ArtifactStore.imageFile(a, id).copyTo(fOrig, true)
                val fTxt = File(dir, "artefato_$id.txt")
                fTxt.writeText(ArtifactStore.reportText(meta))
                val uris = listOf(uri(a, fCom), uri(a, fOrig), uri(a, fTxt))
                a.runOnUiThread {
                    val i = Intent(Intent.ACTION_SEND_MULTIPLE)
                    i.type = "*/*"
                    i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    launch(a, i, uris)
                }
            } catch (_: Exception) {
                a.runOnUiThread { Toast.makeText(a, "Não consegui preparar o compartilhamento", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // Todos os artefatos em um único .zip (print + texto de cada um).
    fun shareAll(a: Activity) {
        Toast.makeText(a, "Preparando o zip...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val list = ArtifactStore.list(a)
                if (list.isEmpty()) {
                    a.runOnUiThread { Toast.makeText(a, "Não há artefatos", Toast.LENGTH_LONG).show() }
                    return@Thread
                }
                val dir = shareDir(a)
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val zip = File(dir, "artefatos_$stamp.zip")
                ZipOutputStream(zip.outputStream().buffered()).use { z ->
                    for (info in list) {
                        val img = ArtifactStore.imageFile(a, info.id)
                        if (img.exists()) {
                            z.putNextEntry(ZipEntry("artefato_${info.id}.jpg"))
                            img.inputStream().use { it.copyTo(z) }
                            z.closeEntry()
                        }
                        val meta = ArtifactStore.meta(a, info.id) ?: continue
                        z.putNextEntry(ZipEntry("artefato_${info.id}.txt"))
                        z.write(ArtifactStore.reportText(meta).toByteArray(Charsets.UTF_8))
                        z.closeEntry()
                    }
                }
                val u = uri(a, zip)
                a.runOnUiThread {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "application/zip"
                    i.putExtra(Intent.EXTRA_STREAM, u)
                    launch(a, i, listOf(u))
                }
            } catch (_: Exception) {
                a.runOnUiThread { Toast.makeText(a, "Não consegui preparar o zip", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
