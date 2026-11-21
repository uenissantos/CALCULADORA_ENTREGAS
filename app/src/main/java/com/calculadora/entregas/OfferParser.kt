package com.calculadora.entregas

import kotlin.math.abs

// Tipos usados pela leitura. O app não procura corrida sozinho: só lê o que foi treinado
// (modelos de imagem em Template.kt e modelos de texto em TextTemplate.kt).
object OfferParser {
    data class TextItem(val text: String, val top: Int, val bottom: Int)

    data class ParsedOffer(
        val amount: Double,
        val km: Double,
        val minutes: Double,
        val top: Int,
        val bottom: Int
    )

    // Com várias corridas, escolhe a que está na linha de leitura.
    fun pick(rides: List<ParsedOffer>, readingY: Int): ParsedOffer? {
        if (rides.isEmpty()) return null
        if (rides.size == 1) return rides[0]
        val inside = rides.firstOrNull { readingY >= it.top && readingY <= it.bottom }
        if (inside != null) return inside
        return rides.minByOrNull { abs((it.top + it.bottom) / 2 - readingY) }
    }
}
