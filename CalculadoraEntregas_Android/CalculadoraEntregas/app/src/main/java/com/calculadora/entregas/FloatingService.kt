package com.calculadora.entregas

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class FloatingService : Service() {
    companion object {
        const val ACTION_UPDATE = "com.calculadora.entregas.UPDATE"
        const val ACTION_STATUS = "com.calculadora.entregas.STATUS"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_DISTANCE = "distance"
        const val EXTRA_PRICE = "price"
        const val EXTRA_APPROVED = "approved"
        const val EXTRA_STATUS = "status"
    }

    private var windowManager: WindowManager? = null
    private var card: TextView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_UPDATE -> {
                val amount = intent.getDoubleExtra(EXTRA_AMOUNT, 0.0)
                val distance = intent.getDoubleExtra(EXTRA_DISTANCE, 0.0)
                val price = intent.getDoubleExtra(EXTRA_PRICE, 0.0)
                val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)
                showCard(
                    "${OfferCalculator.money(price)}/km",
                    "${OfferCalculator.money(amount)}  •  ${OfferCalculator.decimal(distance)} km",
                    if (approved) "ACIMA DO MÍNIMO" else "ABAIXO DO MÍNIMO",
                    approved
                )
            }
            ACTION_STATUS -> {
                showCard("Calculadora ativa", intent.getStringExtra(EXTRA_STATUS) ?: "Aguardando oferta", "", true)
            }
        }
        return START_NOT_STICKY
    }

    private fun showCard(title: String, detail: String, status: String, approved: Boolean) {
        val view = card ?: TextView(this).also {
            it.setTextColor(Color.WHITE)
            it.textSize = 15f
            it.setPadding(18, 13, 18, 13)
            it.setBackgroundColor(Color.rgb(28, 30, 36))
            it.elevation = 12f
            it.maxWidth = (resources.displayMetrics.widthPixels * 0.72f).toInt()
            card = it
            val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 14
                y = 110
            }
            windowManager?.addView(it, params)
        }

        view.text = "$title\\n$detail" + if (status.isNotBlank()) "\\n$status" else ""
        view.setTextColor(if (approved) Color.WHITE else Color.rgb(255, 190, 110))
    }

    override fun onDestroy() {
        card?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        card = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
