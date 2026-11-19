package com.calculadora.entregas

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun fmt(v: Double): String = v.toString().removeSuffix(".0").replace('.', ',')

    private fun num(field: EditText): Double? =
        field.text.toString().trim().replace(',', '.').toDoubleOrNull()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = AppConfig.load(this)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(24), dp(20), dp(24))

        fun add(view: android.view.View) {
            root.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            )
        }

        fun campo(title: String, value: String): EditText {
            val label = TextView(this)
            label.text = title
            label.textSize = 14f
            add(label)
            val input = EditText(this)
            input.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            input.setText(value)
            add(input)
            return input
        }

        val titleView = TextView(this)
        titleView.text = "Calculadora de Entregas v7"
        titleView.textSize = 24f
        titleView.gravity = Gravity.CENTER
        add(titleView)

        val help = TextView(this)
        help.text = "1) Ative a acessibilidade.\n2) Ajuste os valores e salve.\n3) Toque no botão flutuante ON/OFF: abre PRINT (lê a tela na hora e mostra o card) e DESLIGAR/LIGAR."
        help.textSize = 14f
        add(help)

        val accessibility = Button(this)
        accessibility.text = "Ativar acessibilidade"
        accessibility.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        add(accessibility)

        val fHour = campo("Quero por hora, no mínimo (R$)", fmt(cfg.minPerHour))
        val fMinute = campo("Quero por minuto, no mínimo (R$)", fmt(cfg.minPerMinute))
        val fKmGood = campo("KM bom: a partir de (R$/km)", fmt(cfg.kmGood))
        val fKmBad = campo("KM ruim: abaixo de (R$/km)", fmt(cfg.kmBad))
        val fFuel = campo("Preço do litro do combustível (R$)", fmt(cfg.fuelPrice))
        val fKmL = campo("Km que o veículo faz por litro", fmt(cfg.kmPerLiter))
        val fSeconds = campo("Segundos que o card fica na tela", cfg.cardSeconds.toString())
        val fLine = campo("Linha de leitura da lista de corridas (% da altura da tela, 10 a 90)", cfg.linePercent.toString())

        val save = Button(this)
        save.text = "Salvar configurações"
        save.setOnClickListener {
            val hour = num(fHour)
            val minute = num(fMinute)
            val kmGood = num(fKmGood)
            val kmBad = num(fKmBad)
            val fuel = num(fFuel)
            val kmL = num(fKmL)
            val seconds = num(fSeconds)
            val line = num(fLine)
            if (hour == null || minute == null || kmGood == null || kmBad == null ||
                fuel == null || kmL == null || seconds == null || line == null || kmL <= 0.0
            ) {
                Toast.makeText(this, "Preencha todos os campos com números válidos", Toast.LENGTH_LONG).show()
            } else {
                AppConfig.save(
                    this,
                    AppConfig(hour, minute, kmGood, kmBad, fuel, kmL, seconds.toInt().coerceIn(2, 30), line.toInt().coerceIn(10, 90))
                )
                Toast.makeText(this, "Configurações salvas", Toast.LENGTH_SHORT).show()
            }
        }
        add(save)

        val test = Button(this)
        test.text = "Testar card"
        test.setOnClickListener {
            val service = AccessibilityReader.instance
            if (service == null) {
                Toast.makeText(this, "Ative a acessibilidade primeiro", Toast.LENGTH_LONG).show()
            } else {
                service.previewCard()
            }
        }
        add(test)

        val diag = Button(this)
        diag.text = "Diagnóstico (o que o app leu)"
        diag.setOnClickListener {
            val info = TextView(this)
            info.text = DebugLog.report()
            info.textSize = 11f
            info.setTextIsSelectable(true)
            info.setPadding(dp(12), dp(12), dp(12), dp(12))
            val sv = ScrollView(this)
            sv.addView(info)
            android.app.AlertDialog.Builder(this)
                .setTitle("Diagnóstico")
                .setView(sv)
                .setPositiveButton("Fechar", null)
                .show()
        }
        add(diag)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }
}
