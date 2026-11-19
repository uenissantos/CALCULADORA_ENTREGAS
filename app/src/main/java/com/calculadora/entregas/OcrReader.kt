package com.calculadora.entregas

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

// Resultado do OCR: linhas inteiras (para o leitor automático) e palavras com posição (para o modelo treinado).
class OcrResult(val lines: List<OfferParser.TextItem>, val words: List<OcrWord>)

object OcrReader {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // Deixa o reconhecedor pronto para a primeira leitura não demorar.
    fun warmUp() {
        recognizer
    }

    fun read(bitmap: Bitmap, callback: (List<OfferParser.TextItem>?) -> Unit) {
        readFull(bitmap) { callback(it?.lines) }
    }

    fun readFull(bitmap: Bitmap, callback: (OcrResult?) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val items = ArrayList<OfferParser.TextItem>()
                val words = ArrayList<OcrWord>()
                var lineIndex = 0
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        items.add(OfferParser.TextItem(line.text, box.top, box.bottom))
                        for (el in line.elements) {
                            val b = el.boundingBox ?: continue
                            words.add(OcrWord(el.text, b.left, b.top, b.right, b.bottom, lineIndex))
                        }
                        lineIndex++
                    }
                }
                callback(OcrResult(items, words))
            }
            .addOnFailureListener { callback(null) }
    }
}
