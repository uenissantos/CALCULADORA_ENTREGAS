package com.calculadora.entregas

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 40, 28, 28)
        }

        val title = TextView(this).apply {
            text = "Calculadora de Entregas"
            textSize = 25f
            gravity = Gravity.CENTER
        }
        val description = TextView(this).apply {
            text = "Valor mínimo inicial: R$ 2,00 por km\\n\\nAtive as permissões abaixo. O leitor usa acessibilidade e, quando necessário, captura de tela autorizada pelo Android para OCR."
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 24)
        }

        val accessibility = Button(this).apply {
            text = "Ativar acessibilidade"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val overlay = Button(this).apply {
            text = "Permitir sobreposição"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            }
        }

        val test = Button(this).apply {
            text = "Testar cálculo da oferta de exemplo"
            setOnClickListener {
                val result = OfferCalculator.calculate(7.30, 2.1, 1.2)
                description.text = result?.let {
                    "${OfferCalculator.money(it.pricePerKm)}/km\\n" +
                        "Total: ${OfferCalculator.decimal(it.totalKm)} km\\n" +
                        if (it.approved) "Acima do mínimo" else "Abaixo do mínimo"
                } ?: "Não foi possível calcular"
            }
        }

        val note = TextView(this).apply {
            text = "O card só será exibido quando a permissão de sobreposição estiver ativa e uma oferta válida for reconhecida."
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 22, 0, 0)
        }

        listOf(title, description, accessibility, overlay, test, note).forEach { view ->
            root.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 14 }
            )
        }
        setContentView(root)
    }
}
