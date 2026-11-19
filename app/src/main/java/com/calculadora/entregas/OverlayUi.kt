package com.calculadora.entregas

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
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
    private val onPrint: () -> Unit
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var card: View? = null
    private var button: TextView? = null
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
            val b = TextView(service)
            b.gravity = Gravity.CENTER
            b.textSize = 13f
            b.setTextColor(white)
            b.setTypeface(b.typeface, Typeface.BOLD)

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

    private fun pill(content: String, color: Int, action: () -> Unit): TextView {
        val v = TextView(service)
        v.text = content
        v.textSize = 13f
        v.gravity = Gravity.CENTER
        v.setTextColor(white)
        v.setTypeface(v.typeface, Typeface.BOLD)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(22).toFloat()
        bg.setColor(color)
        bg.setStroke(dp(2), white)
        v.background = bg
        v.setOnClickListener {
            closeMenu()
            action()
        }
        return v
    }

    private fun toggleMenu() {
        if (menuViews.isEmpty()) openMenu() else closeMenu()
    }

    private fun openMenu() {
        val bp = buttonParams ?: return
        val w = dp(92)
        val h = dp(44)
        val gap = dp(8)
        val size = dp(52)
        val screenW = service.resources.displayMetrics.widthPixels
        val rightHalf = bp.x + size / 2 > screenW / 2
        val x1 = if (rightHalf) bp.x - gap - w else bp.x + size + gap
        val x2 = if (rightHalf) x1 - gap - w else x1 + w + gap
        val y = bp.y + (size - h) / 2

        val active = AppConfig.isEnabled(service)
        val items = listOf(
            Pair(pill("PRINT", Color.rgb(25, 118, 210)) { onPrint() }, x1),
            Pair(
                pill(if (active) "DESLIGAR" else "LIGAR", if (active) Color.rgb(190, 30, 50) else Color.rgb(0, 150, 80)) { onPower() },
                x2
            )
        )
        for (entry in items) {
            val params = WindowManager.LayoutParams(
                w,
                h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = entry.second
            params.y = y
            try {
                wm.addView(entry.first, params)
                menuViews.add(entry.first)
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
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(if (active) BuildInfo.COLOR else Color.rgb(190, 30, 50))
        bg.setStroke(dp(2), white)
        b.background = bg
        b.text = if (active) "ON" else "OFF"
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
