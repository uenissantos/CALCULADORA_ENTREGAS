package com.calculadora.entregas

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Guarda o que o app leu da tela, para diagnóstico.
object DebugLog {
    var events = 0
    var noRoot = 0
    var noItems = 0
    var screenshotStatus = "ainda não tentou"
    private var screenInfo = "nada lido ainda"
    private var ocrInfo = "nada lido ainda"

    private fun snapshot(items: List<OfferParser.TextItem>, rides: Int): String {
        val sb = StringBuilder()
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        sb.append("Hora ").append(time)
            .append(" | textos: ").append(items.size)
            .append(" | corridas achadas: ").append(rides).append('\n')
        for (item in items.take(60)) {
            sb.append(item.top).append(": ")
                .append(item.text.replace('\n', ' ').take(70)).append('\n')
        }
        return sb.toString()
    }

    private fun relevant(items: List<OfferParser.TextItem>): Boolean =
        items.any { it.text.contains("R$") || it.text.contains("km", ignoreCase = true) }

    fun recordScreen(items: List<OfferParser.TextItem>, rides: Int) {
        if (relevant(items)) screenInfo = snapshot(items, rides)
    }

    fun recordOcr(items: List<OfferParser.TextItem>, rides: Int) {
        ocrInfo = snapshot(items, rides)
    }

    fun report(): String = buildString {
        append("Eventos recebidos: ").append(events).append('\n')
        append("Sem janela ativa: ").append(noRoot).append(" | Sem texto: ").append(noItems).append('\n')
        append("Foto da tela: ").append(screenshotStatus).append("\n\n")
        append("== ÚLTIMA TELA COM VALORES (acessibilidade) ==\n").append(screenInfo).append('\n')
        append("== ÚLTIMA LEITURA POR FOTO (OCR) ==\n").append(ocrInfo)
    }
}
