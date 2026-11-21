package com.calculadora.entregas

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class AccessibilityReader : AccessibilityService() {
    companion object {
        var instance: AccessibilityReader? = null

        private const val MODE_AUTO = 0   // leitura automática (só quando ligada)
        private const val MODE_CALC = 1   // botão calculadora: força a leitura agora
        private const val MODE_SHOT = 2   // botão câmera: print real guardado no app

        private const val DEBOUNCE_MS = 250L
        private const val MIN_GAP_MS = 900L

        private const val THUMB_W = 54
        private const val THUMB_H = 108
    }

    private class Trained(val name: String, val offer: OfferParser.ParsedOffer)

    private var ui: OverlayUi? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastSignature = ""
    private var lastOfferKey = ""
    private var lastThumb: IntArray? = null
    private val screenshotInProgress = AtomicBoolean(false)

    @Volatile
    private var enabled = false
    private var scanPending = false
    private var lastScanAt = 0L
    private var rescan = false

    private val scanRunnable = Runnable {
        scanPending = false
        lastScanAt = SystemClock.uptimeMillis()
        if (enabled) scanScreen()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        enabled = AppConfig.isEnabled(this)
        val overlay = OverlayUi(this, { togglePower() }, { calcNow() }, { shotNow() }, { hideOverlay() })
        ui = overlay
        if (AppConfig.isOverlayVisible(this)) overlay.showButton(enabled)
        applyPower()
        if (enabled) OcrReader.warmUp()
    }

    // Desligado (ou sem botão na tela): o sistema nem entrega eventos ao app. Consumo zero.
    private fun applyPower() {
        val info = serviceInfo ?: return
        val on = enabled && AppConfig.isOverlayVisible(this)
        info.eventTypes = if (on) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
        } else {
            0
        }
        info.notificationTimeout = 300
        serviceInfo = info
    }

    private fun resetState() {
        handler.removeCallbacks(scanRunnable)
        scanPending = false
        rescan = false
        lastThumb = null
        lastSignature = ""
        lastOfferKey = ""
    }

    private fun togglePower() {
        enabled = !enabled
        AppConfig.setEnabled(this, enabled)
        ui?.updateButton(enabled)
        resetState()
        if (!enabled) {
            ui?.hideCard()
            ui?.hideLine()
        }
        applyPower()
        if (enabled) {
            OcrReader.warmUp()
            requestScan(DEBOUNCE_MS)
        }
        toast(if (enabled) "Leitura automática ligada" else "Leitura automática desligada")
    }

    // Tira o botão da tela e desliga tudo. Para voltar: botão "Mostrar botão flutuante" no app.
    fun hideOverlay() {
        AppConfig.setOverlayVisible(this, false)
        enabled = false
        AppConfig.setEnabled(this, false)
        resetState()
        ui?.destroy()
        applyPower()
        toast("Botão removido. Para voltar, abra o app e toque em Mostrar botão flutuante")
    }

    fun showOverlay() {
        AppConfig.setOverlayVisible(this, true)
        ui?.showButton(enabled)
        applyPower()
    }

    // Botão calculadora: lê a tela agora (mesmo desligado) e mostra o card.
    private fun calcNow() {
        if (Build.VERSION.SDK_INT < 30) {
            toast("Leitura por foto exige Android 11 ou mais novo")
            return
        }
        ui?.setHiddenForCapture(true)
        handler.postDelayed({ capture(MODE_CALC) }, 180)
    }

    // Botão câmera: print real da tela, guardado em Artefatos para treinar depois.
    private fun shotNow() {
        if (Build.VERSION.SDK_INT < 30) {
            toast("Print exige Android 11 ou mais novo")
            return
        }
        ui?.setHiddenForCapture(true)
        handler.postDelayed({ capture(MODE_SHOT) }, 180)
    }

    fun previewCard() {
        val cfg = AppConfig.load(this)
        val result = OfferCalculator.calculate(7.30, 3.3, 11.0, cfg) ?: return
        ui?.showCard(result, cfg.cardSeconds)
    }

    private fun readingLineY(heightPx: Int): Int =
        heightPx * AppConfig.load(this).linePercent / 100

    // O evento só agenda uma leitura: nada pesado roda aqui.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !enabled) return
        if (event.packageName?.toString() == packageName) return
        DebugLog.events++
        if (screenshotInProgress.get()) rescan = true
        if (scanPending) return
        val since = SystemClock.uptimeMillis() - lastScanAt
        requestScan(maxOf(DEBOUNCE_MS, MIN_GAP_MS - since))
    }

    private fun requestScan(delayMs: Long) {
        if (scanPending) return
        scanPending = true
        handler.postDelayed(scanRunnable, delayMs)
    }

    private fun scanScreen() {
        val root = rootInActiveWindow
        if (root == null) {
            DebugLog.noRoot++
            return
        }
        if (root.packageName?.toString() == packageName) {
            // A janela ativa é o próprio app: não lê nem sobrescreve o diagnóstico.
            root.recycle()
            return
        }
        if (TemplateStore.hasAny(this)) {
            // Com modelo treinado, a leitura é só pelo modelo (por foto).
            root.recycle()
            capture(MODE_AUTO)
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
            capture(MODE_AUTO)
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

    // ---------- Foto da tela ----------

    private fun grabScreen(onDone: (Bitmap?, Int) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var bmp: Bitmap? = null
                    val hardware = result.hardwareBuffer
                    try {
                        val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                        DebugLog.screenshotStatus = "ok"
                        bmp = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                        wrapped?.recycle()
                    } catch (_: Exception) {
                    } finally {
                        hardware.close()
                    }
                    onDone(bmp, 0)
                }

                override fun onFailure(errorCode: Int) {
                    DebugLog.screenshotStatus = "falhou, código $errorCode (6 = app bloqueia captura de tela)"
                    onDone(null, errorCode)
                }
            }
        )
    }

    private fun capture(mode: Int) {
        if (Build.VERSION.SDK_INT < 30) return
        if (!screenshotInProgress.compareAndSet(false, true)) {
            if (mode != MODE_AUTO) ui?.setHiddenForCapture(false)
            return
        }
        grabScreen { bmp, err ->
            if (mode != MODE_AUTO) ui?.setHiddenForCapture(false)
            if (bmp == null) {
                if (mode != MODE_AUTO) toast("Não consegui tirar o print (código $err)")
                finishCapture()
            } else if (mode == MODE_SHOT) {
                ArtifactStore.save(this, bmp, ArtifactStore.KIND_PRINT, "Print real da tela (para treinar)", null, null)
                bmp.recycle()
                toast("Print guardado em Artefatos")
                finishCapture()
            } else {
                processBitmap(bmp, mode)
            }
        }
    }

    private fun finishCapture() {
        screenshotInProgress.set(false)
        if (rescan) {
            rescan = false
            if (enabled) requestScan(DEBOUNCE_MS)
        }
    }

    // Compara uma miniatura com a anterior: tela parada não gasta leitura (OCR).
    private fun screenChanged(bmp: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(bmp, THUMB_W, THUMB_H, true)
        val px = IntArray(THUMB_W * THUMB_H)
        small.getPixels(px, 0, THUMB_W, 0, 0, THUMB_W, THUMB_H)
        if (small !== bmp) small.recycle()
        val prev = lastThumb
        lastThumb = px
        if (prev == null || prev.size != px.size) return true
        var changed = 0
        for (i in px.indices) {
            val a = px[i]
            val b = prev[i]
            val d = abs(((a shr 16) and 255) - ((b shr 16) and 255)) +
                abs(((a shr 8) and 255) - ((b shr 8) and 255)) +
                abs((a and 255) - (b and 255))
            if (d > 60) changed++
        }
        return changed >= 3
    }

    private fun processBitmap(bmp: Bitmap, mode: Int) {
        val calc = mode == MODE_CALC
        if (!calc && !screenChanged(bmp)) {
            bmp.recycle()
            finishCapture()
            return
        }
        OcrReader.readFull(bmp) { res ->
            try {
                if (res == null) {
                    if (calc) {
                        toast("Falha ao ler o print. Guardado em Artefatos")
                        ArtifactStore.save(this, bmp, ArtifactStore.KIND_NOT_FOUND, "Falha na leitura do texto (OCR)", null, null)
                    }
                } else {
                    val hasModels = TemplateStore.hasAny(this)
                    // Com modelo treinado, só vale o modelo. Sem modelo, leitura por texto.
                    val trained = if (hasModels) readByTemplate(res, bmp.width, bmp.height) else null
                    val rides = when {
                        trained != null -> listOf(trained.offer)
                        hasModels -> emptyList()
                        else -> OfferParser.findRides(res.lines)
                    }
                    if (calc) DebugLog.recordManual(res.lines, rides.size) else DebugLog.recordOcr(res.lines, rides.size)
                    val y = readingLineY(bmp.height)
                    if (rides.isNotEmpty()) {
                        if (calc) lastOfferKey = ""
                        handleRides(rides, y)
                        if (calc && ArtifactStore.keepSuccess(this)) {
                            val p = OfferParser.pick(rides, y)
                            val source = if (trained != null) "modelo ${trained.name}" else "texto da tela"
                            val status = if (p != null) {
                                "Leu " + OfferCalculator.money(p.amount) + " | " + OfferCalculator.decimal(p.km) + " km | " + p.minutes.toInt() + " min (" + source + ")"
                            } else {
                                "Leu uma corrida ($source)"
                            }
                            ArtifactStore.save(this, bmp, ArtifactStore.KIND_READ, status, res.words, modelReadings(res, bmp.width, bmp.height))
                        }
                    } else if (calc) {
                        toast("Nenhuma corrida encontrada. Print e leitura guardados em Artefatos")
                        ArtifactStore.save(
                            this, bmp, ArtifactStore.KIND_NOT_FOUND,
                            if (hasModels) "Nenhuma corrida encontrada pelo modelo treinado" else "Nenhuma corrida encontrada nesta tela",
                            res.words, modelReadings(res, bmp.width, bmp.height)
                        )
                    } else {
                        // A oferta saiu da tela: some o card e libera a mesma oferta para o futuro.
                        lastOfferKey = ""
                        ui?.hideCard()
                        ui?.hideLine()
                    }
                }
            } finally {
                bmp.recycle()
                finishCapture()
            }
        }
    }

    // Tenta cada modelo treinado; o primeiro que ler valor e KM válidos vale.
    private fun readByTemplate(res: OcrResult, w: Int, h: Int): Trained? {
        for (t in TemplateStore.all(this)) {
            val offer = TemplateReader.read(t, res.words, w, h).offer
            if (offer != null) return Trained(t.name, offer)
        }
        return null
    }

    // O que cada modelo leu e onde procurou, para comparar com o print depois.
    private fun modelReadings(res: OcrResult, w: Int, h: Int): JSONArray {
        val arr = JSONArray()
        for (t in TemplateStore.all(this)) {
            val r = TemplateReader.read(t, res.words, w, h)
            val o = JSONObject()
            o.put("nome", t.name)
            o.put("ok", r.offer != null)
            val campos = JSONObject()
            for ((f, text) in r.raw) campos.put(f.label, text)
            o.put("campos", campos)
            val regioes = JSONObject()
            for ((f, rc) in t.fields) {
                val a = JSONArray()
                a.put(rc.left.toDouble())
                a.put(rc.top.toDouble())
                a.put(rc.right.toDouble())
                a.put(rc.bottom.toDouble())
                regioes.put(f.label, a)
            }
            o.put("regioes", regioes)
            arr.put(o)
        }
        return arr
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
        handler.removeCallbacks(scanRunnable)
        ui?.destroy()
        ui = null
        instance = null
        super.onDestroy()
    }
}
