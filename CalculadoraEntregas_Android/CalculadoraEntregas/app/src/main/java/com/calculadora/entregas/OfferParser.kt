package com.calculadora.entregas

object OfferParser {
    data class ParsedOffer(val amount: Double, val km: Double, val minutes: Double)

    private val moneyRegex = Regex("""R\$\s*(\d{1,3}(?:\.\d{3})*,\d{2}|\d+,\d{2})""", RegexOption.IGNORE_CASE)
    private val legRegex = Regex("""(\d+)\s*min\s*\(\s*(\d+(?:[,.]\d+)?)\s*km\s*\)""", RegexOption.IGNORE_CASE)

    // Oferta: "R$7,30", "8 min (2,1 km)" (coleta) e "3 min (1,2 km)" (entrega).
    fun parse(text: String): ParsedOffer? {
        val t = text.replace('\u00A0', ' ')
        val legs = legRegex.findAll(t).toList()
        if (legs.size < 2) return null

        val firstLegStart = legs[0].range.first
        val moneys = moneyRegex.findAll(t).toList()
        val chosen = moneys.lastOrNull { it.range.first < firstLegStart }
            ?: moneys.firstOrNull()
            ?: return null
        val amount = chosen.groupValues[1].replace(".", "").replace(",", ".").toDoubleOrNull()
            ?: return null

        var km = 0.0
        var minutes = 0.0
        for (leg in legs.take(2)) {
            minutes += leg.groupValues[1].toDoubleOrNull() ?: 0.0
            km += leg.groupValues[2].replace(",", ".").toDoubleOrNull() ?: 0.0
        }
        return ParsedOffer(amount, km, minutes)
    }
}
