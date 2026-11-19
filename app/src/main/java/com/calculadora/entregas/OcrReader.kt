package com.calculadora.entregas

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

object OcrReader {
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // Deixa o reconhecedor pronto para a primeira leitura não demorar.
    fun warmUp() {
        recognizer
    }

    fun read(bitmap: Bitmap, callback: (List<OfferParser.TextItem>?) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val items = ArrayList<OfferParser.TextItem>()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val box = line.boundingBox ?: continue
                        items.add(OfferParser.TextItem(line.text, box.top, box.bottom))
                    }
                }
                callback(items)
            }
            .addOnFailureListener { callback(null) }
    }
}
