package com.calculadora.entregas

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
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
    private var lastSignature = ""
    private var lastOfferKey = ""
    private var lastOcrTime = 0L
    private val screenshotInProgress = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val overlay = OverlayUi(this) { toggle() }
        ui = overlay
        overlay.showButton(AppConfig.isEnabled(this))
        OcrReader.warmUp()
    }

    private fun toggle() {
        val now = !AppConfig.isEnabled(this)
        AppConfig.setEnabled(this, now)
        ui?.updateButton(now)
        if (!now) {
            ui?.hideCard()
            ui?.hideLine()
            lastOfferKey = ""
            lastSignature = ""
        }
    }

    fun previewCard() {
        val cfg = AppConfig.load(this)
        val result = OfferCalculator.calculate(7.30, 3.3, 11.0, cfg) ?: return
        ui?.showCard(result, cfg.cardSeconds)
    }

    private fun readingLineY(heightPx: Int): Int =
        heightPx * AppConfig.load(this).linePercent / 100

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() == packageName) return
        if (!AppConfig.isEnabled(this)) return

        DebugLog.events++
        val root = rootInActiveWindow
        if (root == null) {
            DebugLog.noRoot++
            return
        }
        val items = ArrayList<OfferParser.TextItem>()
        collectItems(root, items, Rect())
        root.recycle()
        if (items.isEmpty()) {
            DebugLog.noItems++
            return
        }

        val signature = items.joinToString("|") { "${it.text}@${it.top}" }
        if (signature == lastSignature) return
        lastSignature = signature

        val rides = OfferParser.findRides(items)
        DebugLog.recordScreen(items, rides.size)
        if (rides.isNotEmpty()) {
            handleRides(rides, readingLineY(resources.displayMetrics.heightPixels))
        } else {
            captureForOcr()
        }
    }

    private fun collectItems(node: AccessibilityNodeInfo?, out: MutableList<OfferParser.TextItem>, rect: Rect) {
        if (node == null) return
        if (!node.isVisibleToUser) return
        val sb = StringBuilder()
        node.text?.let { sb.append(it).append(' ') }
        node.contentDescription?.let { sb.append(it) }
        if (sb.isNotBlank()) {
            node.getBoundsInScreen(rect)
            out.add(OfferParser.TextItem(sb.toString(), rect.top, rect.bottom))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectItems(child, out, rect)
            child?.recycle()
        }
    }

    private fun captureForOcr() {
        if (Build.VERSION.SDK_INT < 30) return
        val now = System.currentTimeMillis()
        if (now - lastOcrTime < 600) return
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
                        DebugLog.screenshotStatus = "ok"
                        val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        wrapped?.recycle()
                        if (bitmap == null) {
                            screenshotInProgress.set(false)
                            return
                        }
                        OcrReader.read(bitmap) { items ->
                            try {
                                if (items != null) {
                                    val rides = OfferParser.findRides(items)
                                    DebugLog.recordOcr(items, rides.size)
                                    if (rides.isNotEmpty()) {
                                        handleRides(rides, readingLineY(bitmap.height))
                                    } else {
                                        // A oferta saiu da tela: some o card e libera a mesma oferta para o futuro.
                                        lastOfferKey = ""
                                        ui?.hideCard()
                                        ui?.hideLine()
                                    }
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
                    DebugLog.screenshotStatus = "falhou, código $errorCode (6 = app bloqueia captura de tela)"
                    screenshotInProgress.set(false)
                }
            }
        )
    }

    private fun handleRides(rides: List<OfferParser.ParsedOffer>, readingY: Int) {
        val listMode = rides.distinctBy { "${it.amount}|${it.km}|${it.minutes}" }.size > 1
        val ride = OfferParser.pick(rides, readingY) ?: return
        if (listMode) ui?.showLine(readingY) else ui?.hideLine()
        publishOffer(ride, listMode)
    }

    private fun publishOffer(offer: OfferParser.ParsedOffer, persist: Boolean) {
        val key = "${offer.amount}|${offer.km}|${offer.minutes}"
        if (key == lastOfferKey) return
        val cfg = AppConfig.load(this)
        val result = OfferCalculator.calculate(offer.amount, offer.km, offer.minutes, cfg) ?: return
        lastOfferKey = key
        // Na lista de corridas o card fica fixo e acompanha a corrida da linha de leitura.
        ui?.showCard(result, if (persist) 0 else cfg.cardSeconds)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        ui?.destroy()
        ui = null
        instance = null
        super.onDestroy()
    }
}
