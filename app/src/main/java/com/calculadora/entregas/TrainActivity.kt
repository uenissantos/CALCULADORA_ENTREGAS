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
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

// Tela "Treinar": o usuário escolhe um print da galeria (tela inteira com o card)
// e marca onde fica cada item. O modelo vale para o app indicado no nome.
class TrainActivity : Activity() {
    private val pickCode = 1001
    private val chips = HashMap<Field, Button>()
    private var selected: Field = Field.VALOR
    private var bitmap: Bitmap? = null
    private lateinit var trainView: TrainView
    private lateinit var nameField: EditText

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun fieldColor(f: Field): Int = when (f) {
        Field.VALOR -> Color.rgb(0, 150, 80)
        Field.KM_UNICO -> Color.rgb(230, 81, 0)
        Field.KM_IDA -> Color.rgb(198, 40, 40)
        Field.KM_VOLTA -> Color.rgb(156, 39, 176)
        Field.MIN_UNICO -> Color.rgb(0, 131, 143)
        Field.MIN_IDA -> Color.rgb(21, 101, 192)
        Field.MIN_VOLTA -> Color.rgb(93, 64, 55)
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.textSize = 13f
        b.minHeight = 0
        b.minimumHeight = dp(40)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun hrow(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            r.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return r
    }

    private fun chip(f: Field): Button {
        val b = button(f.label) {
            selected = f
            refreshChips()
        }
        b.setOnLongClickListener {
            trainView.rects.remove(f)
            trainView.invalidate()
            refreshChips()
            true
        }
        chips[f] = b
        return b
    }

    private fun refreshChips() {
        for ((f, b) in chips) {
            val marked = trainView.rects.containsKey(f)
            b.text = (if (marked) "✓ " else "") + f.label
            if (f == selected) {
                b.setBackgroundColor(fieldColor(f))
                b.setTextColor(Color.WHITE)
            } else {
                b.setBackgroundColor(Color.rgb(235, 235, 235))
                b.setTextColor(fieldColor(f))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(8), dp(32), dp(8), dp(24))

        trainView = TrainView(this)

        nameField = EditText(this)
        nameField.inputType = InputType.TYPE_CLASS_TEXT
        nameField.hint = "Nome do app (pacote), ex.: com.app.exemplo"
        nameField.textSize = 14f
        nameField.setText(TemplateStore.lastPackage() ?: "")
        root.addView(nameField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(
            hrow(
                button("Apps recentes") { pickRecent() },
                button("Modelos salvos") { pickSaved() }
            )
        )
        root.addView(
            hrow(
                button("Escolher imagem da galeria") { pickImage() },
                button("Testar leitura") { testReading() }
            )
        )

        val help = TextView(this)
        help.text = "Escolha o print da tela inteira com o card de corrida. Toque no campo abaixo e arraste um retângulo sobre ele na imagem. " +
            "Toque longo no campo apaga a marcação. O que não marcar fica em branco. " +
            "KM/Tempo: use \"único\" se o card já mostra o total; senão marque ida e volta."
        help.textSize = 12f
        help.setPadding(dp(4), dp(4), dp(4), dp(4))
        root.addView(help)

        root.addView(hrow(chip(Field.VALOR), chip(Field.KM_UNICO)))
        root.addView(hrow(chip(Field.KM_IDA), chip(Field.KM_VOLTA)))
        root.addView(hrow(chip(Field.MIN_UNICO), chip(Field.MIN_IDA), chip(Field.MIN_VOLTA)))

        root.addView(trainView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(
            hrow(
                button("Salvar modelo") { saveTemplate() },
                button("Apagar modelo") { deleteTemplate() }
            )
        )

        setContentView(root)
        refreshChips()
    }

    @Suppress("DEPRECATION")
    private fun pickImage() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.type = "image/*"
        i.addCategory(Intent.CATEGORY_OPENABLE)
        startActivityForResult(Intent.createChooser(i, "Escolha o print"), pickCode)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val b = loadBitmap(uri)
        if (b == null) {
            toast("Não consegui abrir a imagem")
        } else {
            bitmap = b
            trainView.bmp = b
            trainView.invalidate()
        }
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

    private fun pickRecent() {
        val list = TemplateStore.recentPackages()
        if (list.isEmpty()) {
            toast("Abra o app de corrida com a acessibilidade ativa e volte aqui: ele aparece nesta lista")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Apps recentes")
            .setItems(list.toTypedArray()) { _, which -> nameField.setText(list[which]) }
            .show()
    }

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
                nameField.setText(t.name)
                trainView.rects.clear()
                for ((f, r) in t.fields) trainView.rects[f] = RectF(r)
                trainView.invalidate()
                refreshChips()
            }
            .show()
    }

    private fun saveTemplate() {
        val name = nameField.text.toString().trim()
        val rects = trainView.rects
        if (name.isEmpty()) {
            toast("Digite o nome do app (use \"Apps recentes\")")
            return
        }
        if (!rects.containsKey(Field.VALOR)) {
            toast("Marque o valor da rota")
            return
        }
        if (!rects.containsKey(Field.KM_UNICO) && !rects.containsKey(Field.KM_IDA) && !rects.containsKey(Field.KM_VOLTA)) {
            toast("Marque o KM (único, ou ida e volta)")
            return
        }
        TemplateStore.save(this, Template(name, LinkedHashMap(rects)))
        toast("Modelo salvo para $name")
    }

    private fun deleteTemplate() {
        val name = nameField.text.toString().trim()
        if (name.isEmpty() || TemplateStore.get(this, name) == null) {
            toast("Não há modelo salvo com esse nome")
            return
        }
        TemplateStore.delete(this, name)
        toast("Modelo apagado: $name")
    }

    private fun testReading() {
        val b = bitmap
        if (b == null) {
            toast("Escolha a imagem primeiro")
            return
        }
        if (trainView.rects.isEmpty()) {
            toast("Marque pelo menos o valor e o KM")
            return
        }
        toast("Lendo a imagem...")
        val marked = LinkedHashMap(trainView.rects)
        OcrReader.readFull(b) { res ->
            if (res == null) {
                toast("Falha ao ler a imagem")
            } else {
                val reading = TemplateReader.read(Template("teste", marked), res.words, b.width, b.height)
                val sb = StringBuilder()
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
                AlertDialog.Builder(this)
                    .setTitle("O que o app leu")
                    .setMessage(sb.toString())
                    .setPositiveButton("Fechar", null)
                    .show()
            }
        }
    }

    // Mostra a imagem e deixa arrastar retângulos; guarda tudo em proporção (0..1).
    inner class TrainView(ctx: Context) : View(ctx) {
        val rects = LinkedHashMap<Field, RectF>()
        var bmp: Bitmap? = null
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

        private fun toScreen(r: RectF): RectF = RectF(
            dst.left + r.left * dst.width(),
            dst.top + r.top * dst.height(),
            dst.left + r.right * dst.width(),
            dst.top + r.bottom * dst.height()
        )

        private fun drawMark(c: Canvas, f: Field, r: RectF) {
            val color = fieldColor(f)
            val p = toScreen(r)
            fill.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
            stroke.color = color
            c.drawRect(p, fill)
            c.drawRect(p, stroke)
            c.drawText(f.short, p.left + dp(3), p.top + dp(14), label)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.rgb(30, 30, 30))
            val b = bmp
            if (b == null) {
                label.textAlign = Paint.Align.CENTER
                canvas.drawText("Nenhuma imagem escolhida", width / 2f, height / 2f, label)
                label.textAlign = Paint.Align.LEFT
                return
            }
            computeDst()
            canvas.drawBitmap(b, null, dst, null)
            for ((f, r) in rects) drawMark(canvas, f, r)
            val s = dragStart
            val n = dragNow
            if (s != null && n != null) {
                val color = fieldColor(selected)
                stroke.color = color
                canvas.drawRect(minOf(s.x, n.x), minOf(s.y, n.y), maxOf(s.x, n.x), maxOf(s.y, n.y), stroke)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (bmp == null) return false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
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
            if (s != null && n != null && dst.width() > 0f && dst.height() > 0f) {
                val l = ((minOf(s.x, n.x) - dst.left) / dst.width()).coerceIn(0f, 1f)
                val t = ((minOf(s.y, n.y) - dst.top) / dst.height()).coerceIn(0f, 1f)
                val r = ((maxOf(s.x, n.x) - dst.left) / dst.width()).coerceIn(0f, 1f)
                val b = ((maxOf(s.y, n.y) - dst.top) / dst.height()).coerceIn(0f, 1f)
                if (r - l > 0.01f && b - t > 0.004f) {
                    rects[selected] = RectF(l, t, r, b)
                    refreshChips()
                }
            }
            invalidate()
        }
    }
}
