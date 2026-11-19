package com.calculadora.entregas

import java.util.Locale

object OfferCalculator {
    data class Result(
        val amount: Double,
        val totalKm: Double,
        val minutes: Double,
        val perKm: Double,
        val perHour: Double?,
        val perMinute: Double?,
        val cost: Double,
        val costPerKm: Double,
        val profit: Double,
        val kmLevel: Int, // 2 = bom, 1 = regular, 0 = ruim
        val hourGood: Boolean?,
        val minuteGood: Boolean?
    )

    fun calculate(amount: Double, km: Double, minutes: Double, cfg: AppConfig): Result? {
        if (!amount.isFinite() || !km.isFinite() || !minutes.isFinite()) return null
        if (amount <= 0.0 || km <= 0.0 || minutes < 0.0) return null

        val perKm = amount / km
        val hasTime = minutes > 0.0
        val perMinute = if (hasTime) amount / minutes else null
        val perHour = if (perMinute != null) perMinute * 60.0 else null
        val costPerKm = if (cfg.kmPerLiter > 0.0) cfg.fuelPrice / cfg.kmPerLiter else 0.0
        val cost = costPerKm * km
        val level = when {
            perKm >= cfg.kmGood -> 2
            perKm < cfg.kmBad -> 0
            else -> 1
        }
        return Result(
            amount = amount,
            totalKm = km,
            minutes = minutes,
            perKm = perKm,
            perHour = perHour,
            perMinute = perMinute,
            cost = cost,
            costPerKm = costPerKm,
            profit = amount - cost,
            kmLevel = level,
            hourGood = perHour?.let { it >= cfg.minPerHour },
            minuteGood = perMinute?.let { it >= cfg.minPerMinute }
        )
    }

    fun money(value: Double): String = String.format(Locale("pt", "BR"), "R$ %.2f", value)

    fun decimal(value: Double): String = String.format(Locale("pt", "BR"), "%.1f", value)
}
