package com.calculadora.entregas

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class OverlayUi(
    private val service: AccessibilityService,
    private val onPower: () -> Unit,
    private val onCalc: () -> Unit,
    private val onShot: () -> Unit,
    private val onClose: () -> Unit
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var card: View? = null
    private var button: IconView? = null
    private var line: View? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private val menuViews = ArrayList<View>()
    private val closeMenuRunnable = Runnable { closeMenu() }
    private var lineParams: WindowManager.LayoutParams? = null
    private val hideRunnable = Runnable { hideCard() }

    private val green = Color.rgb(0, 230, 118)
    private val yellow = Color.rgb(255, 214, 0)
    private val red = Color.rgb(255, 23, 68)
    private val white = Color.WHITE
    private val gray = Color.WHITE
    private val orange = Color.rgb(255, 145, 0)
    private val dim = Color.rgb(160, 165, 175)
    private val cyan = Color.rgb(64, 196, 255)

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    private fun label(content: String, size: Float, color: Int, bold: Boolean): TextView {
        val view = TextView(service)
        view.text = content
        view.textSize = size
        view.setTextColor(color)
        view.gravity = Gravity.CENTER
        if (bold) view.setTypeface(view.typeface, Typeface.BOLD)
        return view
    }

    private fun cell(title: String, value: String, status: String?, color: Int): LinearLayout {
        val col = LinearLayout(service)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.addView(label(title, 13f, gray, false))
        col.addView(label(value, 17f, color, true))
        if (status != null) col.addView(label(status, 15f, color, true))
        return col
    }

    private fun row(vararg cells: View): LinearLayout {
        val line = LinearLayout(service)
        line.orientation = LinearLayout.HORIZONTAL
        line.setPadding(0, dp(8), 0, dp(4))
        for (c in cells) {
            line.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        return line
    }

    private fun buildCard(r: OfferCalculator.Result): View {
        val box = LinearLayout(service)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14), dp(12), dp(14), dp(12))
        val bg = GradientDrawable()
        bg.setColor(Color.rgb(18, 20, 26))
        bg.cornerRadius = dp(18).toFloat()
        bg.setStroke(dp(3), Color.rgb(74, 123, 208))
        box.background = bg

        val header = if (r.minutes > 0.0) {
            String.format(java.util.Locale("pt", "BR"), "%skm, %.0f min.", OfferCalculator.decimal(r.totalKm), r.minutes)
        } else {
            OfferCalculator.decimal(r.totalKm) + "km"
        }
        box.addView(label(header, 18f, white, true))

        val kmColor = when (r.kmLevel) { 2 -> green; 1 -> yellow; else -> red }
        val kmStatus = when (r.kmLevel) { 2 -> "BOM"; 1 -> "REGULAR"; else -> "RUIM" }
        val hourColor = when (r.hourGood) { true -> green; false -> red; null -> dim }
        val minuteColor = when (r.minuteGood) { true -> green; false -> red; null -> dim }
        val hourStatus = when (r.hourGood) { true -> "BOM"; false -> "RUIM"; null -> "SEM TEMPO" }
        val minuteStatus = when (r.minuteGood) { true -> "BOM"; false -> "RUIM"; null -> "SEM TEMPO" }
        val hourValue = r.perHour?.let { OfferCalculator.money(it) } ?: "—"
        val minuteValue = r.perMinute?.let { OfferCalculator.money(it) } ?: "—"

        box.addView(
            row(
                cell("KM", OfferCalculator.money(r.perKm), kmStatus, kmColor),
                cell("Hora", hourValue, hourStatus, hourColor),
                cell("Minuto", minuteValue, minuteStatus, minuteColor)
            )
        )
        box.addView(
            row(
                cell("Custo Total", OfferCalculator.money(r.cost), null, orange),
                cell("Custo/KM", OfferCalculator.money(r.costPerKm), null, cyan),
                cell("Lucro", OfferCalculator.money(r.profit), null, if (r.profit >= 0) green else red)
            )
        )
        return box
    }

    fun showCard(r: OfferCalculator.Result, seconds: Int) {
        removeCardView()
        val view = buildCard(r)
        val params = WindowManager.LayoutParams(
            (service.resources.displayMetrics.widthPixels * 0.82f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = dp(48)
        try {
            wm.addView(view, params)
            card = view
        } catch (_: Exception) {
        }
        handler.removeCallbacks(hideRunnable)
        if (seconds > 0) handler.postDelayed(hideRunnable, seconds * 1000L)
    }

    private fun removeCardView() {
        card?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        card = null
    }

    fun hideCard() {
        handler.removeCallbacks(hideRunnable)
        removeCardView()
    }

    fun showLine(y: Int) {
        val existing = line
        val current = lineParams
        if (existing != null && current != null) {
            if (current.y != y) {
                current.y = y
                try { wm.updateViewLayout(existing, current) } catch (_: Exception) {}
            }
            return
        }
        val v = View(service)
        v.setBackgroundColor(Color.argb(170, 255, 214, 0))
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(3),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.y = y
        try {
            wm.addView(v, p)
            line = v
            lineParams = p
        } catch (_: Exception) {
        }
    }

    fun hideLine() {
        line?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        line = null
        lineParams = null
    }

    fun showButton(active: Boolean) {
        if (button == null) {
            val size = dp(52)
            val b = IconView(service, IconView.MAIN)
            b.contentDescription = "Botão da calculadora"

            val params = WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = dp(8)
            params.y = dp(220)

            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            b.setOnTouchListener { v, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = params.x
                        startY = params.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) {
                            if (!moved) closeMenu()
                            moved = true
                        }
                        if (moved) {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) toggleMenu()
                        true
                    }
                    else -> false
                }
            }
            try {
                wm.addView(b, params)
                button = b
                buttonParams = params
            } catch (_: Exception) {
            }
        }
        updateButton(active)
    }

    private class MenuDef(val kind: Int, val color: Int, val desc: String, val action: () -> Unit)

    private fun toggleMenu() {
        if (menuViews.isEmpty()) openMenu() else closeMenu()
    }

    // Os ícones abrem em arco ao redor do botão principal, virados para o centro da tela.
    private fun openMenu() {
        val bp = buttonParams ?: return
        val dm = service.resources.displayMetrics
        val size = dp(52)
        val item = dp(46)
        val radius = dp(78)
        val cx = bp.x + size / 2.0
        val cy = bp.y + size / 2.0
        val dir = Math.atan2(dm.heightPixels / 2.0 - cy, dm.widthPixels / 2.0 - cx)
        val step = Math.toRadians(48.0)

        val active = AppConfig.isEnabled(service)
        val defs = listOf(
            MenuDef(IconView.POWER, if (active) Color.rgb(0, 150, 80) else Color.rgb(190, 30, 50), "Ligar ou desligar a leitura automática") { onPower() },
            MenuDef(IconView.CALC, Color.rgb(25, 118, 210), "Calcular agora") { onCalc() },
            MenuDef(IconView.CAMERA, Color.rgb(123, 31, 162), "Tirar print e guardar no app") { onShot() },
            MenuDef(IconView.CLOSE, Color.rgb(90, 90, 90), "Tirar o botão da tela") { onClose() }
        )
        for (i in defs.indices) {
            val def = defs[i]
            val angle = dir + (i - 1.5) * step
            var x = (cx + radius * Math.cos(angle) - item / 2.0).toInt()
            var y = (cy + radius * Math.sin(angle) - item / 2.0).toInt()
            x = x.coerceIn(dp(4), maxOf(dp(4), dm.widthPixels - item - dp(4)))
            y = y.coerceIn(dp(24), maxOf(dp(24), dm.heightPixels - item - dp(24)))

            val v = IconView(service, def.kind)
            v.fillColor = def.color
            v.contentDescription = def.desc
            v.setOnClickListener {
                closeMenu()
                def.action()
            }
            val params = WindowManager.LayoutParams(
                item,
                item,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = x
            params.y = y
            try {
                wm.addView(v, params)
                menuViews.add(v)
            } catch (_: Exception) {
            }
        }
        handler.removeCallbacks(closeMenuRunnable)
        handler.postDelayed(closeMenuRunnable, 6000L)
    }

    fun closeMenu() {
        handler.removeCallbacks(closeMenuRunnable)
        for (v in menuViews) {
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        menuViews.clear()
    }

    // Esconde tudo por um instante para o print sair limpo (sem o card por cima).
    fun setHiddenForCapture(hidden: Boolean) {
        val mode = if (hidden) View.INVISIBLE else View.VISIBLE
        card?.visibility = mode
        line?.visibility = mode
        button?.visibility = mode
        for (v in menuViews) v.visibility = mode
    }

    fun updateButton(active: Boolean) {
        val b = button ?: return
        b.fillColor = if (active) BuildInfo.COLOR else Color.rgb(190, 30, 50)
        b.off = !active
    }

    fun destroy() {
        closeMenu()
        hideCard()
        hideLine()
        button?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        button = null
    }
}

// Ícones desenhados no código (sem depender de fontes ou imagens).
class IconView(ctx: Context, private val kind: Int) : View(ctx) {
    companion object {
        const val MAIN = 0
        const val POWER = 1
        const val CALC = 2
        const val CAMERA = 3
        const val CLOSE = 4
    }

    var fillColor: Int = Color.GRAY
        set(value) {
            field = value
            invalidate()
        }
    var off: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ic = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    init {
        fill.style = Paint.Style.FILL
        ring.style = Paint.Style.STROKE
        ring.color = Color.WHITE
        ic.style = Paint.Style.STROKE
        ic.color = Color.WHITE
        ic.strokeCap = Paint.Cap.ROUND
        ic.strokeJoin = Paint.Join.ROUND
        icFill.style = Paint.Style.FILL
        icFill.color = Color.WHITE
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val cx = w / 2f
        val cy = height / 2f
        ring.strokeWidth = w * 0.045f
        fill.color = fillColor
        val r = w / 2f - ring.strokeWidth / 2f
        c.drawCircle(cx, cy, r, fill)
        c.drawCircle(cx, cy, r, ring)
        ic.strokeWidth = w * 0.07f

        when (kind) {
            MAIN -> {
                c.drawCircle(cx, cy, 0.2f * w, ic)
                c.drawCircle(cx, cy, 0.07f * w, icFill)
                if (off) c.drawLine(cx - 0.27f * w, cy + 0.27f * w, cx + 0.27f * w, cy - 0.27f * w, ic)
            }
            POWER -> {
                val oy = cy + 0.03f * w
                rect.set(cx - 0.2f * w, oy - 0.2f * w, cx + 0.2f * w, oy + 0.2f * w)
                c.drawArc(rect, -60f, 300f, false, ic)
                c.drawLine(cx, cy - 0.27f * w, cx, cy, ic)
            }
            CALC -> {
                rect.set(cx - 0.22f * w, cy - 0.28f * w, cx + 0.22f * w, cy + 0.28f * w)
                c.drawRoundRect(rect, 0.05f * w, 0.05f * w, ic)
                rect.set(cx - 0.14f * w, cy - 0.2f * w, cx + 0.14f * w, cy - 0.1f * w)
                c.drawRect(rect, icFill)
                for (row in 0..2) {
                    for (col in 0..2) {
                        c.drawCircle(cx + (col - 1) * 0.11f * w, cy + 0.02f * w + row * 0.1f * w, 0.03f * w, icFill)
                    }
                }
            }
            CAMERA -> {
                rect.set(cx - 0.27f * w, cy - 0.12f * w, cx + 0.27f * w, cy + 0.2f * w)
                c.drawRoundRect(rect, 0.06f * w, 0.06f * w, ic)
                path.reset()
                path.moveTo(cx - 0.12f * w, cy - 0.12f * w)
                path.lineTo(cx - 0.07f * w, cy - 0.2f * w)
                path.lineTo(cx + 0.07f * w, cy - 0.2f * w)
                path.lineTo(cx + 0.12f * w, cy - 0.12f * w)
                c.drawPath(path, ic)
                c.drawCircle(cx, cy + 0.04f * w, 0.09f * w, ic)
            }
            else -> {
                c.drawLine(cx - 0.16f * w, cy - 0.16f * w, cx + 0.16f * w, cy + 0.16f * w, ic)
                c.drawLine(cx - 0.16f * w, cy + 0.16f * w, cx + 0.16f * w, cy - 0.16f * w, ic)
            }
        }
    }
}
