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
    companion object {
        var instance: AccessibilityReader? = null
    }

    private var ui: OverlayUi? = null
    private var lastText = ""
    private var lastOfferKey = ""
    private var lastOcrTime = 0L
    private val screenshotInProgress = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val overlay = OverlayUi(this) { toggle() }
        ui = overlay
        overlay.showButton(AppConfig.isEnabled(this))
    }

    private fun toggle() {
        val now = !AppConfig.isEnabled(this)
        AppConfig.setEnabled(this, now)
        ui?.updateButton(now)
        if (!now) {
            ui?.hideCard()
            lastOfferKey = ""
            lastText = ""
        }
    }

    fun previewCard() {
        val cfg = AppConfig.load(this)
        val result = OfferCalculator.calculate(7.30, 3.3, 11.0, cfg) ?: return
        ui?.showCard(result, cfg.cardSeconds)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName) return
        if (!AppConfig.isEnabled(this)) return

        val root = rootInActiveWindow
        val text = StringBuilder()
        collectText(root, text)
        root?.recycle()

        val screenText = text.toString()
        if (screenText.isBlank()) return

        if (screenText != lastText) {
            lastText = screenText
            val offer = OfferParser.parse(screenText)
            if (offer != null) {
                publishOffer(offer)
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
        if (Build.VERSION.SDK_INT < 30) return
        val now = System.currentTimeMillis()
        if (now - lastOcrTime < 1200) return
        if (!screenshotInProgress.compareAndSet(false, true)) return
        lastOcrTime = now

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
                                val offer = if (recognized.isNullOrBlank()) null else OfferParser.parse(recognized)
                                if (offer != null) {
                                    publishOffer(offer)
                                } else {
                                    // A oferta saiu da tela: some o card e libera a mesma oferta para o futuro.
                                    lastOfferKey = ""
                                    ui?.hideCard()
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

    private fun publishOffer(offer: OfferParser.ParsedOffer) {
        val key = "${offer.amount}|${offer.km}|${offer.minutes}"
        if (key == lastOfferKey) return
        val cfg = AppConfig.load(this)
        val result = OfferCalculator.calculate(offer.amount, offer.km, offer.minutes, cfg) ?: return
        lastOfferKey = key
        ui?.showCard(result, cfg.cardSeconds)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        ui?.destroy()
        ui = null
        instance = null
        super.onDestroy()
    }
}
