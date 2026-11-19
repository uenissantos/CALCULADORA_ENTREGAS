package com.calculadora.entregas

import kotlin.math.abs

object OfferParser {
    data class TextItem(val text: String, val top: Int, val bottom: Int)

    data class ParsedOffer(
        val amount: Double,
        val km: Double,
        val minutes: Double,
        val top: Int,
        val bottom: Int
    )

    private class Token(
        val isPrice: Boolean,
        val amount: Double,
        val minutes: Double,
        val km: Double,
        val top: Int,
        val bottom: Int
    )

    // Valor: ignora "R$1,58/km" e "R$0,98 Tarifa base".
    private val moneyRegex = Regex(
        """R\$\s*(\d{1,3}(?:\.\d{3})*,\d{2}|\d+,\d{2})(?!\d)(?!\s*/)(?!\s*Tarifa)""",
        RegexOption.IGNORE_CASE
    )

    // Trechos: "8 min (2,1 km)" e "(9 min 4,6 km)".
    private val legRegex = Regex(
        """(?:\(\s*)?(\d+)\s*min\s*(?:\(\s*)?(\d+(?:[,.]\d+)?)\s*km\s*\)""",
        RegexOption.IGNORE_CASE
    )

    // Encontra todas as corridas visíveis: um valor seguido de dois trechos.
    fun findRides(items: List<TextItem>): List<ParsedOffer> {
        val tokens = ArrayList<Token>()
        for (item in items.sortedBy { it.top }) {
            val t = item.text.replace('\u00A0', ' ')
            val local = ArrayList<Pair<Int, Token>>()
            for (m in moneyRegex.findAll(t)) {
                val amount = m.groupValues[1].replace(".", "").replace(",", ".").toDoubleOrNull() ?: continue
                local.add(m.range.first to Token(true, amount, 0.0, 0.0, item.top, item.bottom))
            }
            for (m in legRegex.findAll(t)) {
                val minutes = m.groupValues[1].toDoubleOrNull() ?: continue
                val km = m.groupValues[2].replace(",", ".").toDoubleOrNull() ?: continue
                local.add(m.range.first to Token(false, 0.0, minutes, km, item.top, item.bottom))
            }
            local.sortBy { it.first }
            for (pair in local) tokens.add(pair.second)
        }

        val rides = ArrayList<ParsedOffer>()
        var price: Token? = null
        var legs = ArrayList<Token>()
        for (tk in tokens) {
            if (tk.isPrice) {
                // Um valor novo com trecho solto = corrida anterior cortada; recomeça.
                if (legs.size == 1) legs = ArrayList()
                if (legs.isEmpty()) price = tk
            } else {
                legs.add(tk)
                if (legs.size == 2) {
                    val p = price
                    if (p != null) {
                        rides.add(
                            ParsedOffer(
                                amount = p.amount,
                                km = legs[0].km + legs[1].km,
                                minutes = legs[0].minutes + legs[1].minutes,
                                top = minOf(p.top, legs[0].top),
                                bottom = maxOf(p.bottom, legs[1].bottom)
                            )
                        )
                    }
                    price = null
                    legs = ArrayList()
                }
            }
        }
        return rides
    }

    // Com várias corridas na tela, escolhe a que está na linha de leitura.
    fun pick(rides: List<ParsedOffer>, readingY: Int): ParsedOffer? {
        if (rides.isEmpty()) return null
        if (rides.size == 1) return rides[0]
        val inside = rides.firstOrNull { readingY >= it.top && readingY <= it.bottom }
        if (inside != null) return inside
        return rides.minByOrNull { abs((it.top + it.bottom) / 2 - readingY) }
    }
}
