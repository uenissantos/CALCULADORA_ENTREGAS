package com.calculadora.entregas

import android.content.Context

data class AppConfig(
    val minPerHour: Double,
    val minPerMinute: Double,
    val kmGood: Double,
    val kmBad: Double,
    val fuelPrice: Double,
    val kmPerLiter: Double,
    val cardSeconds: Int
) {
    companion object {
        private fun prefs(c: Context) = c.getSharedPreferences("config", Context.MODE_PRIVATE)

        private fun get(c: Context, key: String, def: Double): Double =
            prefs(c).getString(key, null)?.toDoubleOrNull() ?: def

        fun load(c: Context) = AppConfig(
            minPerHour = get(c, "hora", 30.0),
            minPerMinute = get(c, "minuto", 0.5),
            kmGood = get(c, "kmBom", 2.0),
            kmBad = get(c, "kmRuim", 1.5),
            fuelPrice = get(c, "litro", 6.0),
            kmPerLiter = get(c, "kmLitro", 30.0),
            cardSeconds = get(c, "segundos", 8.0).toInt()
        )

        fun save(c: Context, cfg: AppConfig) {
            prefs(c).edit()
                .putString("hora", cfg.minPerHour.toString())
                .putString("minuto", cfg.minPerMinute.toString())
                .putString("kmBom", cfg.kmGood.toString())
                .putString("kmRuim", cfg.kmBad.toString())
                .putString("litro", cfg.fuelPrice.toString())
                .putString("kmLitro", cfg.kmPerLiter.toString())
                .putString("segundos", cfg.cardSeconds.toString())
                .apply()
        }

        fun isEnabled(c: Context): Boolean = prefs(c).getBoolean("ativo", true)

        fun setEnabled(c: Context, value: Boolean) {
            prefs(c).edit().putBoolean("ativo", value).apply()
        }
    }
}
