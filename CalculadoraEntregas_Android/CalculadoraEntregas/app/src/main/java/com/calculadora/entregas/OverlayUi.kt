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
    private val onToggle: () -> Unit
) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var card: View? = null
    private var button: TextView? = null
    private val hideRunnable = Runnable { hideCard() }

    private val green = Color.rgb(0, 230, 118)
    private val yellow = Color.rgb(255, 214, 0)
    private val red = Color.rgb(255, 23, 68)
    private val white = Color.WHITE
    private val gray = Color.rgb(190, 196, 208)

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
        bg.setColor(Color.argb(245, 24, 26, 32))
        bg.cornerRadius = dp(18).toFloat()
        bg.setStroke(dp(3), Color.rgb(74, 123, 208))
        box.background = bg

        val header = String.format(java.util.Locale("pt", "BR"), "%skm, %.0f min.", OfferCalculator.decimal(r.totalKm), r.minutes)
        box.addView(label(header, 18f, white, true))

        val kmColor = when (r.kmLevel) { 2 -> green; 1 -> yellow; else -> red }
        val kmStatus = when (r.kmLevel) { 2 -> "BOM"; 1 -> "REGULAR"; else -> "RUIM" }
        val hourColor = if (r.hourGood) green else red
        val minuteColor = if (r.minuteGood) green else red

        box.addView(
            row(
                cell("KM", OfferCalculator.money(r.perKm), kmStatus, kmColor),
                cell("Hora", OfferCalculator.money(r.perHour), if (r.hourGood) "BOM" else "RUIM", hourColor),
                cell("Minuto", OfferCalculator.money(r.perMinute), if (r.minuteGood) "BOM" else "RUIM", minuteColor)
            )
        )
        box.addView(
            row(
                cell("Custo Total", OfferCalculator.money(r.cost), null, white),
                cell("Custo/KM", OfferCalculator.money(r.costPerKm), null, white),
                cell("Lucro", OfferCalculator.money(r.profit), null, if (r.profit >= 0) white else red)
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
        handler.postDelayed(hideRunnable, seconds.coerceAtLeast(2) * 1000L)
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
                        if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                        if (moved) {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            try { wm.updateViewLayout(v, params) } catch (_: Exception) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) onToggle()
                        true
                    }
                    else -> false
                }
            }
            try {
                wm.addView(b, params)
                button = b
            } catch (_: Exception) {
            }
        }
        updateButton(active)
    }

    fun updateButton(active: Boolean) {
        val b = button ?: return
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(if (active) Color.rgb(0, 150, 80) else Color.rgb(190, 30, 50))
        bg.setStroke(dp(2), white)
        b.background = bg
        b.text = if (active) "ON" else "OFF"
    }

    fun destroy() {
        hideCard()
        button?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        button = null
    }
}
