package com.calculadora.entregas

import java.text.NumberFormat
import java.util.Locale

object OfferCalculator {
    const val MINIMUM_PER_KM = 2.00

    data class Result(
        val amount: Double,
        val pickupKm: Double,
        val deliveryKm: Double,
        val totalKm: Double,
        val pricePerKm: Double,
        val approved: Boolean
    )

    fun calculate(amount: Double, pickupKm: Double, deliveryKm: Double): Result? {
        if (!amount.isFinite() || !pickupKm.isFinite() || !deliveryKm.isFinite()) return null
        if (amount <= 0.0 || pickupKm < 0.0 || deliveryKm <= 0.0) return null
        val total = pickupKm + deliveryKm
        if (total <= 0.0) return null
        val perKm = amount / total
        return Result(amount, pickupKm, deliveryKm, total, perKm, perKm >= MINIMUM_PER_KM)
    }

    fun money(value: Double): String =
        NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(value)

    fun decimal(value: Double): String =
        String.format(Locale("pt", "BR"), "%.1f", value)
}
