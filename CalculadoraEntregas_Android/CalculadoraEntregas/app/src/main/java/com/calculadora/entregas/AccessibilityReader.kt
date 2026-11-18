package com.calculadora.entregas

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

class AccessibilityReader : AccessibilityService() {
    private var lastText = ""
    private var lastOfferKey = ""
    private val screenshotInProgress = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        showStatus("Leitura ativada")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val root = rootInActiveWindow
        val text = StringBuilder()
        collectText(root, text)
        root?.recycle()

        val screenText = text.toString()
        if (screenText.isBlank()) return

        if (screenText != lastText) {
            lastText = screenText
            val result = OfferParser.calculate(screenText)
            if (result != null) {
                publishOffer(result)
            } else {
                captureForOcr()
            }
        }
    }

    private fun collectText(node: AccessibilityNodeInfo?, output: StringBuilder) {
        if (node == null) return
        node.text?.let { output.append(it).append(' ') }
        node.contentDescription?.let { output.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectText(child, output)
            child?.recycle()
        }
    }

    private fun captureForOcr() {
        if (Build.VERSION.SDK_INT < 30 || !screenshotInProgress.compareAndSet(false, true)) return

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardware = result.hardwareBuffer
                    try {
                        val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                        val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        wrapped?.recycle()
                        if (bitmap == null) {
                            screenshotInProgress.set(false)
                            return
                        }
                        OcrReader.read(bitmap) { recognized ->
                            try {
                                if (!recognized.isNullOrBlank()) {
                                    val offer = OfferParser.calculate(recognized)
                                    if (offer != null) publishOffer(offer)
                                }
                            } finally {
                                bitmap.recycle()
                                screenshotInProgress.set(false)
                            }
                        }
                    } catch (_: Exception) {
                        screenshotInProgress.set(false)
                    } finally {
                        hardware.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    screenshotInProgress.set(false)
                }
            }
        )
    }

    private fun publishOffer(result: OfferCalculator.Result) {
        val key = "${result.amount}|${result.pickupKm}|${result.deliveryKm}"
        if (key == lastOfferKey) return
        lastOfferKey = key

        val intent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_UPDATE
            putExtra(FloatingService.EXTRA_AMOUNT, result.amount)
            putExtra(FloatingService.EXTRA_DISTANCE, result.totalKm)
            putExtra(FloatingService.EXTRA_PRICE, result.pricePerKm)
            putExtra(FloatingService.EXTRA_APPROVED, result.approved)
        }
        try {
            startService(intent)
        } catch (_: Exception) {
            // O Android pode impedir o início do serviço em determinadas condições.
        }
    }

    private fun showStatus(message: String) {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        val intent = Intent(this, FloatingService::class.java).apply {
            action = FloatingService.ACTION_STATUS
            putExtra(FloatingService.EXTRA_STATUS, message)
        }
        try { startService(intent) } catch (_: Exception) {}
    }

    override fun onInterrupt() = Unit
}
