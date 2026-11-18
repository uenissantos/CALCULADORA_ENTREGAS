package com.calculadora.entregas

object OfferParser {
    data class ParsedOffer(val amount: Double, val distances: List<Double>)

    private val moneyRegex = Regex("""R\$\s*([\d.]+,\d{2})""", RegexOption.IGNORE_CASE)
    private val distanceRegex = Regex("""(\d+(?:[,.]\d+)?)\s*km\b""", RegexOption.IGNORE_CASE)

    fun parse(text: String): ParsedOffer? {
        val normalized = text.replace('\u00A0', ' ')
        val amountText = moneyRegex.find(normalized)?.groupValues?.get(1) ?: return null
        val amount = amountText.replace(".", "").replace(",", ".").toDoubleOrNull() ?: return null
        val distances = distanceRegex.findAll(normalized)
            .mapNotNull { it.groupValues[1].replace(",", ".").toDoubleOrNull() }
            .toList()

        // A oferta-modelo mostra dois trechos: deslocamento até a coleta e até a entrega.
        if (distances.size < 2) return null
        return ParsedOffer(amount, distances.take(2))
    }

    fun calculate(text: String): OfferCalculator.Result? {
        val parsed = parse(text) ?: return null
        return OfferCalculator.calculate(parsed.amount, parsed.distances[0], parsed.distances[1])
    }
}
