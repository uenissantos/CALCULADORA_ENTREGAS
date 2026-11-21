package com.calculadora.entregas

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

// Tela "Treinar": imagem da galeria em tela cheia. Arraste sobre um item; aparece um
// flutuante para dizer se é Valor, KM ou Tempo. Embaixo: apagar e confirmar (salvar).
class TrainActivity : Activity() {
    private val pickCode = 1001
    private val createCode = 1002
    private val openCode = 1003
    private val artifactCode = 1004
    private var bitmap: Bitmap? = null
    private var loadedName: String? = null
    private var popup: View? = null
    private lateinit var root: FrameLayout
    private lateinit var trainView: TrainView
    private lateinit var emptyBox: LinearLayout
    private lateinit var hint: TextView

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun fieldColor(f: Field): Int = when (f) {
        Field.VALOR -> Color.rgb(0, 170, 90)
        Field.KM_UNICO -> Color.rgb(255, 109, 0)
        Field.KM_IDA -> Color.rgb(229, 57, 53)
        Field.KM_VOLTA -> Color.rgb(171, 71, 188)
        Field.MIN_UNICO -> Color.rgb(0, 172, 193)
        Field.MIN_IDA -> Color.rgb(30, 136, 229)
        Field.MIN_VOLTA -> Color.rgb(141, 110, 99)
    }

    private fun rounded(color: Int, radiusDp: Int, stroke: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radiusDp).toFloat()
        g.setStroke(dp(2), stroke)
        return g
    }

    private fun circle(symbol: String, color: Int, onClick: () -> Unit): TextView {
        val v = TextView(this)
        v.text = symbol
        v.textSize = 24f
        v.gravity = Gravity.CENTER
        v.setTextColor(Color.WHITE)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        g.setStroke(dp(2), Color.WHITE)
        v.background = g
        v.setOnClickListener { onClick() }
        return v
    }

    private fun place(v: View, sizeDp: Int, gravity: Int, left: Int, top: Int, right: Int, bottom: Int) {
        val lp = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp), gravity)
        lp.setMargins(dp(left), dp(top), dp(right), dp(bottom))
        root.addView(v, lp)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        trainView = TrainView(this)
        root.addView(trainView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        emptyBox = LinearLayout(this)
        emptyBox.orientation = LinearLayout.VERTICAL
        emptyBox.gravity = Gravity.CENTER_HORIZONTAL
        val info = TextView(this)
        info.text = "Escolha o print da tela inteira com o card de corrida"
        info.textSize = 16f
        info.setTextColor(Color.WHITE)
        info.gravity = Gravity.CENTER
        info.setPadding(dp(32), dp(8), dp(32), dp(16))
        emptyBox.addView(info)
        val pick = Button(this)
        pick.text = "Escolher imagem da galeria"
        pick.isAllCaps = false
        pick.setOnClickListener { pickSource() }
        emptyBox.addView(pick)
        root.addView(emptyBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        hint = TextView(this)
        hint.text = "Arraste um retângulo sobre o valor, o KM ou o tempo"
        hint.textSize = 13f
        hint.setTextColor(Color.WHITE)
        hint.setPadding(dp(12), dp(6), dp(12), dp(6))
        hint.background = rounded(Color.argb(170, 0, 0, 0), 12, Color.TRANSPARENT)
        hint.visibility = View.GONE
        val hlp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        hlp.topMargin = dp(100)
        root.addView(hint, hlp)

        // Em cima: trocar imagem e abrir modelos salvos.
        place(circle("🖼", Color.argb(200, 30, 30, 30)) { pickSource() }, 48, Gravity.TOP or Gravity.START, 12, 36, 0, 0)
        place(circle("📋", Color.argb(200, 30, 30, 30)) { pickSaved() }, 48, Gravity.TOP or Gravity.START, 68, 36, 0, 0)
        place(circle("💾", Color.argb(200, 30, 30, 30)) { backupMenu() }, 48, Gravity.TOP or Gravity.START, 124, 36, 0, 0)

        // Embaixo, por cima da foto: apagar e confirmar.
        place(circle("🗑", Color.rgb(198, 40, 40)) { askDelete() }, 60, Gravity.BOTTOM or Gravity.START, 24, 0, 0, 56)
        place(circle("✓", Color.rgb(0, 150, 80)) { confirm() }, 60, Gravity.BOTTOM or Gravity.END, 0, 0, 24, 56)

        setContentView(root)
    }

    private fun pickSource() {
        AlertDialog.Builder(this)
            .setTitle("Imagem para treinar")
            .setItems(arrayOf("Galeria", "Prints guardados no app (Artefatos)")) { _, which ->
                if (which == 0) pickImage() else pickArtifact()
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(i, "Escolha o print"), pickCode)
    }

    @Suppress("DEPRECATION")
    private fun pickArtifact() {
        val i = Intent(this, ArtifactsActivity::class.java)
        i.putExtra(ArtifactsActivity.EXTRA_PICK, true)
        startActivityForResult(i, artifactCode)
    }

    private fun showBitmap(b: Bitmap) {
        bitmap = b
        trainView.bmp = b
        emptyBox.visibility = View.GONE
        hint.visibility = if (trainView.rects.isEmpty()) View.VISIBLE else View.GONE
        dismissPopup()
        trainView.invalidate()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            pickCode -> {
                val uri = data?.data ?: return
                val b = loadBitmap(uri)
                if (b == null) toast("Não consegui abrir a imagem") else showBitmap(b)
            }
            artifactCode -> {
                val id = data?.getStringExtra(ArtifactsActivity.EXTRA_ID) ?: return
                val b = ArtifactRender.decode(ArtifactStore.imageFile(this, id), 2600)
                if (b == null) toast("Não consegui abrir o print") else showBitmap(b)
            }
            createCode -> {
                val uri = data?.data ?: return
                doBackup(uri)
            }
            openCode -> {
                val uri = data?.data ?: return
                doRestore(uri)
            }
        }
    }

    // ---------- Backup e restauração ----------

    private fun backupMenu() {
        AlertDialog.Builder(this)
            .setTitle("Backup dos modelos")
            .setItems(arrayOf("Fazer backup (salvar arquivo)", "Restaurar backup (abrir arquivo)")) { _, which ->
                if (which == 0) startBackup() else startRestore()
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun startBackup() {
        if (!TemplateStore.hasAny(this)) {
            toast("Não há modelo salvo para fazer backup")
            return
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/zip"
        i.putExtra(Intent.EXTRA_TITLE, "backup_modelos_$stamp.zip")
        startActivityForResult(i, createCode)
    }

    @Suppress("DEPRECATION")
    private fun startRestore() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, openCode)
    }

    private fun doBackup(uri: Uri) {
        Thread {
            val msg = try {
                val n = contentResolver.openOutputStream(uri)?.use { TemplateStore.backupTo(this, it) } ?: -1
                if (n < 0) "Não consegui gravar o arquivo" else "Backup feito: $n modelo(s) com as imagens"
            } catch (_: Exception) {
                "Falha ao fazer o backup"
            }
            runOnUiThread { toast(msg) }
        }.start()
    }

    private fun doRestore(uri: Uri) {
        Thread {
            val msg = try {
                val n = contentResolver.openInputStream(uri)?.use { TemplateStore.restoreFrom(this, it) } ?: -1
                if (n < 0) "Não consegui abrir o arquivo" else if (n == 0) "Nenhum modelo encontrado nesse arquivo" else "Restaurado: $n modelo(s)"
            } catch (_: Exception) {
                "Arquivo de backup inválido"
            }
            runOnUiThread { toast(msg) }
        }.start()
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outHeight / sample > 2600 || bounds.outWidth / sample > 2600) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }

    // ---------- Flutuante Valor / KM / Tempo ----------

    private fun dismissPopup() {
        popup?.let { root.removeView(it) }
        popup = null
        trainView.pending = null
        trainView.invalidate()
    }

    private fun showPopup(rect: RectF, existing: Field?) {
        dismissPopup()
        trainView.pending = RectF(rect)
        trainView.invalidate()
        showLevel(0, rect, existing)
    }

    private fun assign(rect: RectF, f: Field, existing: Field?) {
        if (existing != null) trainView.rects.remove(existing)
        trainView.rects[f] = RectF(rect)
        hint.visibility = View.GONE
        dismissPopup()
    }

    private fun showLevel(level: Int, rect: RectF, existing: Field?) {
        popup?.let { root.removeView(it) }
        popup = null

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setPadding(dp(6), dp(6), dp(6), dp(6))
        bar.background = rounded(Color.argb(235, 25, 25, 25), 16, Color.WHITE)

        fun opt(label: String, color: Int, action: () -> Unit) {
            val t = TextView(this)
            t.text = label
            t.textSize = 15f
            t.gravity = Gravity.CENTER
            t.setTextColor(Color.WHITE)
            t.setTypeface(t.typeface, Typeface.BOLD)
            t.setPadding(dp(14), 0, dp(14), 0)
            t.background = rounded(color, 12, Color.TRANSPARENT)
            t.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44))
            lp.setMargins(dp(3), 0, dp(3), 0)
            bar.addView(t, lp)
        }

        val gray = Color.rgb(90, 90, 90)
        when (level) {
            0 -> {
                opt("Valor", fieldColor(Field.VALOR)) { assign(rect, Field.VALOR, existing) }
                opt("KM", fieldColor(Field.KM_UNICO)) { showLevel(1, rect, existing) }
                opt("Tempo", fieldColor(Field.MIN_UNICO)) { showLevel(2, rect, existing) }
                if (existing != null) {
                    opt("Apagar", Color.rgb(198, 40, 40)) {
                        trainView.rects.remove(existing)
                        dismissPopup()
                    }
                }
                opt("✕", gray) { dismissPopup() }
            }
            1 -> {
                opt("Único", fieldColor(Field.KM_UNICO)) { assign(rect, Field.KM_UNICO, existing) }
                opt("Ida", fieldColor(Field.KM_IDA)) { assign(rect, Field.KM_IDA, existing) }
                opt("Volta", fieldColor(Field.KM_VOLTA)) { assign(rect, Field.KM_VOLTA, existing) }
                opt("←", gray) { showLevel(0, rect, existing) }
            }
            else -> {
                opt("Único", fieldColor(Field.MIN_UNICO)) { assign(rect, Field.MIN_UNICO, existing) }
                opt("Ida", fieldColor(Field.MIN_IDA)) { assign(rect, Field.MIN_IDA, existing) }
                opt("Volta", fieldColor(Field.MIN_VOLTA)) { assign(rect, Field.MIN_VOLTA, existing) }
                opt("←", gray) { showLevel(0, rect, existing) }
            }
        }

        bar.measure(
            View.MeasureSpec.makeMeasureSpec(root.width, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(root.height, View.MeasureSpec.AT_MOST)
        )
        val w = bar.measuredWidth
        val h = bar.measuredHeight
        val sr = trainView.toScreen(rect)
        val x = (sr.centerX() - w / 2f).toInt().coerceIn(dp(8), maxOf(dp(8), root.width - w - dp(8)))
        var y = (sr.bottom + dp(10)).toInt()
        if (y + h > root.height - dp(120)) y = (sr.top - h - dp(10)).toInt()
        y = y.coerceIn(dp(90), maxOf(dp(90), root.height - h - dp(120)))
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = x
        lp.topMargin = y
        root.addView(bar, lp)
        popup = bar
    }

    // ---------- Modelos, apagar e confirmar ----------

    private fun pickSaved() {
        val list = TemplateStore.all(this)
        if (list.isEmpty()) {
            toast("Nenhum modelo salvo ainda")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Modelos salvos")
            .setItems(list.map { it.name }.toTypedArray()) { _, which ->
                val t = list[which]
                dismissPopup()
                loadedName = t.name
                trainView.rects.clear()
                for ((f, r) in t.fields) trainView.rects[f] = RectF(r)
                hint.visibility = View.GONE
                val saved = TemplateStore.loadImage(this, t.name)
                if (saved != null) {
                    showBitmap(saved)
                    hint.visibility = View.GONE
                } else if (bitmap == null) {
                    toast("Modelo carregado, mas sem imagem guardada. Escolha uma imagem (ícone no canto).")
                }
                trainView.invalidate()
            }
            .show()
    }

    private fun askDelete() {
        val builder = AlertDialog.Builder(this)
            .setTitle("Apagar")
            .setMessage("Apagar todas as marcações da tela?")
            .setPositiveButton("Apagar marcações") { _, _ ->
                dismissPopup()
                trainView.rects.clear()
                hint.visibility = if (bitmap != null) View.VISIBLE else View.GONE
                trainView.invalidate()
            }
            .setNegativeButton("Cancelar", null)
        val name = loadedName
        if (name != null) {
            builder.setNeutralButton("Apagar modelo salvo") { _, _ ->
                TemplateStore.delete(this, name)
                loadedName = null
                dismissPopup()
                trainView.rects.clear()
                trainView.invalidate()
                toast("Modelo apagado: $name")
            }
        }
        builder.show()
    }

    private fun confirm() {
        dismissPopup()
        val rects = trainView.rects
        if (!rects.containsKey(Field.VALOR)) {
            toast("Marque o valor da rota")
            return
        }
        if (!rects.containsKey(Field.KM_UNICO) && !rects.containsKey(Field.KM_IDA) && !rects.containsKey(Field.KM_VOLTA)) {
            toast("Marque o KM (único, ou ida e volta)")
            return
        }
        val marked = LinkedHashMap(rects)
        val b = bitmap
        if (b == null) {
            askSave(marked, null)
            return
        }
        toast("Lendo a imagem...")
        OcrReader.readFull(b) { res ->
            if (res == null) {
                toast("Falha ao ler a imagem")
            } else {
                askSave(marked, TemplateReader.read(Template("teste", marked), res.words, b.width, b.height))
            }
        }
    }

    private fun report(marked: Map<Field, RectF>, reading: TemplateReader.Reading?): String {
        val sb = StringBuilder()
        if (reading == null) {
            sb.append("Sem imagem para conferir a leitura.")
            return sb.toString()
        }
        for (f in Field.values()) {
            val shown = if (!marked.containsKey(f)) {
                "(em branco)"
            } else {
                val t = reading.raw[f] ?: ""
                if (t.isBlank()) "(nada lido)" else t
            }
            sb.append(f.label).append(": ").append(shown).append('\n')
        }
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

    private fun askSave(marked: Map<Field, RectF>, reading: TemplateReader.Reading?) {
        AlertDialog.Builder(this)
            .setTitle("O que o app leu")
            .setMessage(report(marked, reading))
            .setPositiveButton("Salvar") { _, _ -> askName(marked) }
            .setNegativeButton("Voltar", null)
            .show()
    }

    // Você escolhe o nome. Com o mesmo nome do modelo aberto, substitui; com outro nome, cria um novo.
    private fun askName(marked: Map<Field, RectF>) {
        val current = loadedName
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setSingleLine()
        input.setText(current ?: TemplateStore.nextName(this))
        input.setSelection(input.text.length)
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("Nome do modelo")
            .setView(box)
            .setPositiveButton("Salvar") { _, _ ->
                val name = input.text.toString().trim().take(40)
                if (name.isEmpty()) {
                    toast("Digite um nome")
                } else if (name != current && TemplateStore.exists(this, name)) {
                    AlertDialog.Builder(this)
                        .setTitle("Já existe")
                        .setMessage("Já existe um modelo chamado \"$name\". Substituir?")
                        .setPositiveButton("Substituir") { _, _ -> persist(marked, name) }
                        .setNegativeButton("Cancelar", null)
                        .show()
                } else {
                    persist(marked, name)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // Guarda os retângulos e uma cópia da imagem dentro do app (não some se apagar da galeria).
    private fun persist(marked: Map<Field, RectF>, name: String) {
        TemplateStore.save(this, Template(name, LinkedHashMap(marked)))
        val b = bitmap
        if (b != null) TemplateStore.saveImage(this, name, b)
        loadedName = name
        toast("Modelo salvo: $name")
        finish()
    }

    // ---------- Imagem + retângulos ----------

    inner class TrainView(ctx: Context) : View(ctx) {
        val rects = LinkedHashMap<Field, RectF>()
        var bmp: Bitmap? = null
        var pending: RectF? = null
        private var dragStart: PointF? = null
        private var dragNow: PointF? = null
        private val dst = RectF()
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = dp(3).toFloat()
            fill.style = Paint.Style.FILL
            label.color = Color.WHITE
            label.textSize = dp(12).toFloat()
            label.typeface = Typeface.DEFAULT_BOLD
            label.setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }

        private fun computeDst() {
            val b = bmp ?: return
            val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
            val w = b.width * s
            val h = b.height * s
            val l = (width - w) / 2f
            val t = (height - h) / 2f
            dst.set(l, t, l + w, t + h)
        }

        fun toScreen(r: RectF): RectF {
            computeDst()
            return RectF(
                dst.left + r.left * dst.width(),
                dst.top + r.top * dst.height(),
                dst.left + r.right * dst.width(),
                dst.top + r.bottom * dst.height()
            )
        }

        private fun drawMark(c: Canvas, f: Field, r: RectF) {
            val color = fieldColor(f)
            val p = toScreen(r)
            fill.color = Color.argb(80, Color.red(color), Color.green(color), Color.blue(color))
            stroke.color = color
            c.drawRect(p, fill)
            c.drawRect(p, stroke)
            c.drawText(f.short, p.left + dp(3), p.top - dp(4), label)
        }

        override fun onDraw(canvas: Canvas) {
            val b = bmp
            if (b == null) return
            computeDst()
            canvas.drawBitmap(b, null, dst, null)
            for ((f, r) in rects) drawMark(canvas, f, r)
            val pd = pending
            if (pd != null) {
                val p = toScreen(pd)
                fill.color = Color.argb(60, 255, 255, 255)
                stroke.color = Color.WHITE
                canvas.drawRect(p, fill)
                canvas.drawRect(p, stroke)
            }
            val s = dragStart
            val n = dragNow
            if (s != null && n != null) {
                stroke.color = Color.WHITE
                canvas.drawRect(minOf(s.x, n.x), minOf(s.y, n.y), maxOf(s.x, n.x), maxOf(s.y, n.y), stroke)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bmp == null) return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dismissPopup()
                    dragStart = PointF(e.x, e.y)
                    dragNow = PointF(e.x, e.y)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    dragNow = PointF(e.x, e.y)
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    dragNow = PointF(e.x, e.y)
                    finishDrag()
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragStart = null
                    dragNow = null
                    invalidate()
                }
            }
            return true
        }

        private fun finishDrag() {
            val s = dragStart
            val n = dragNow
            dragStart = null
            dragNow = null
            computeDst()
            if (s != null && n != null && dst.width() > 0f && dst.height() > 0f) {
                val w = abs(n.x - s.x)
                val h = abs(n.y - s.y)
                if (w < dp(12) && h < dp(12)) {
                    // Toque simples: se foi sobre uma marcação, reabre o flutuante dela.
                    val hit = rects.entries.toList().asReversed().firstOrNull { toScreen(it.value).contains(n.x, n.y) }
                    if (hit != null) showPopup(RectF(hit.value), hit.key)
                } else {
                    val l = ((minOf(s.x, n.x) - dst.left) / dst.width()).coerceIn(0f, 1f)
                    val t = ((minOf(s.y, n.y) - dst.top) / dst.height()).coerceIn(0f, 1f)
                    val r = ((maxOf(s.x, n.x) - dst.left) / dst.width()).coerceIn(0f, 1f)
                    val b = ((maxOf(s.y, n.y) - dst.top) / dst.height()).coerceIn(0f, 1f)
                    if (r - l > 0.01f && b - t > 0.004f) showPopup(RectF(l, t, r, b), null)
                }
            }
            invalidate()
        }
    }
}
