package com.calculadora.entregas

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
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

        private const val DEBOUNCE_MS = 400L
        private const val MIN_GAP_MS = 2000L

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
    private val power by lazy { getSystemService(POWER_SERVICE) as android.os.PowerManager }

    @Volatile
    private var enabled = false
    private var currentPkg: String? = null
    private var modelsVersion = -1
    private var modelsCache: List<Template> = emptyList()
    private var textCache: List<TextModel> = emptyList()

    // Telas que nunca são corrida: menu/tela inicial do celular e barra do sistema.
    private val ignoredPackages: Set<String> by lazy {
        val set = hashSetOf(
            "com.android.systemui",
            "com.sec.android.app.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.android.launcher3",
            "com.miui.home",
            "com.motorola.launcher3"
        )
        try {
            val i = Intent(Intent.ACTION_MAIN)
            i.addCategory(Intent.CATEGORY_HOME)
            for (ri in packageManager.queryIntentActivities(i, 0)) set.add(ri.activityInfo.packageName)
        } catch (_: Exception) {
        }
        set
    }

    private fun reloadModels() {
        if (modelsVersion != TemplateStore.version) {
            modelsCache = TemplateStore.all(this)
            textCache = TextStore.all(this)
            modelsVersion = TemplateStore.version
        }
    }

    // Modelos de imagem que valem para o app da tela: os ligados a ele e os sem app (imagem da galeria).
    private fun modelsFor(pkg: String?): List<Template> {
        reloadModels()
        return modelsCache.filter { it.pkg == null || it.pkg == pkg }
    }

    // Modelos de texto que valem para o app da tela.
    private fun textModelsFor(pkg: String?): List<TextModel> {
        reloadModels()
        return textCache.filter { it.pkg == null || it.pkg == pkg }
    }

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
        // Print real: esconde só o botão; o card que estiver na tela sai no print.
        ui?.setHiddenForCapture(true, keepCard = true)
        handler.postDelayed({ capture(MODE_SHOT) }, 180)
    }

    fun previewCard() {
        val cfg = AppConfig.load(this)
        val result = OfferCalculator.calculate(7.30, 3.3, 11.0, cfg) ?: return
        ui?.showCard(result, cfg.cardSeconds)
    }

    // O evento só agenda uma leitura: nada pesado roda aqui.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !enabled) return
        if (event.packageName?.toString() == packageName) return
        // Tela apagada: não lê nada.
        if (!power.isInteractive) return
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
        if (!power.isInteractive) return
        val root = rootInActiveWindow
        if (root == null) {
            DebugLog.noRoot++
            return
        }
        val pkg = root.packageName?.toString()
        if (pkg == packageName) {
            // A janela ativa é o próprio app: não lê nem sobrescreve o diagnóstico.
            root.recycle()
            return
        }
        if (pkg != null && pkg in ignoredPackages) {
            // Menu do celular, tela inicial, barra do sistema: nunca é corrida.
            root.recycle()
            lastOfferKey = ""
            ui?.hideCard()
            ui?.hideLine()
            return
        }
        currentPkg = pkg
        val region = modelsFor(pkg)
        val texts = textModelsFor(pkg)
        if (region.isEmpty() && texts.isEmpty()) {
            // Sem treino para este app: o app não gera card nenhum.
            root.recycle()
            if (lastOfferKey.isNotEmpty()) {
                lastOfferKey = ""
                ui?.hideCard()
                ui?.hideLine()
            }
            return
        }
        val items = ArrayList<OfferParser.TextItem>()
        collectItems(root, items, Rect())
        root.recycle()

        // Precisa de print se houver modelo de imagem ou de texto da foto.
        val needShot = region.isNotEmpty() || texts.any { it.src == TextSrc.OCR }
        val accModels = texts.filter { it.src == TextSrc.ACC }
        if (accModels.isNotEmpty()) {
            val signature = items.joinToString("|") { "${it.text}@${it.top}" }
            if (signature != lastSignature) {
                lastSignature = signature
                DebugLog.recordScreen(items, 0)
                val hit = readByText(accModels, items)
                if (hit != null) {
                    publishOffer(hit.offer)
                    return
                }
            } else if (!needShot) {
                return
            }
        }
        if (needShot) {
            capture(MODE_AUTO, items)
        } else {
            // Treino de texto da tela e a corrida não está lá: some o card.
            lastOfferKey = ""
            ui?.hideCard()
            ui?.hideLine()
        }
    }

    // Textos da tela pela acessibilidade: cada nó visível com texto.
    private fun collectItems(node: AccessibilityNodeInfo?, out: MutableList<OfferParser.TextItem>, rect: Rect) {
        if (node == null) return
        if (!node.isVisibleToUser) return
        val t = node.text?.toString()?.trim().orEmpty()
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        val s = if (d.isEmpty() || d == t) t else if (t.isEmpty()) d else "$t $d"
        if (s.isNotBlank()) {
            node.getBoundsInScreen(rect)
            out.add(OfferParser.TextItem(s, rect.top, rect.bottom))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            collectItems(child, out, rect)
            child?.recycle()
        }
    }

    // Textos da tela agora (para os botões calculadora e câmera).
    private fun snapshotItems(): List<OfferParser.TextItem> {
        val root = rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() == packageName) {
            root.recycle()
            return emptyList()
        }
        val out = ArrayList<OfferParser.TextItem>()
        collectItems(root, out, Rect())
        root.recycle()
        return out
    }

    private fun readByText(models: List<TextModel>, items: List<OfferParser.TextItem>): Trained? {
        for (m in models) {
            val o = TextReader.read(m, items).offer
            if (o != null) return Trained("texto ${m.name}", o)
        }
        return null
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

    private fun foregroundPackage(): String? {
        val root = rootInActiveWindow ?: return null
        val p = root.packageName?.toString()
        root.recycle()
        return if (p == packageName) null else p
    }

    private fun capture(mode: Int, autoItems: List<OfferParser.TextItem>? = null) {
        if (Build.VERSION.SDK_INT < 30) return
        if (!screenshotInProgress.compareAndSet(false, true)) {
            if (mode != MODE_AUTO) ui?.setHiddenForCapture(false)
            return
        }
        val pkg: String? = if (mode == MODE_AUTO) currentPkg else foregroundPackage()
        val acc: List<OfferParser.TextItem> = if (mode == MODE_AUTO) (autoItems ?: emptyList()) else snapshotItems()
        // Se o nosso card está na tela, os textos dele não podem ser lidos como se fossem da corrida.
        val cardRect = ui?.cardBounds()
        grabScreen { bmp, err ->
            if (mode != MODE_AUTO) ui?.setHiddenForCapture(false)
            if (bmp == null) {
                if (mode == MODE_AUTO) {
                    finishCapture()
                } else {
                    // App bloqueou o print: segue com tela preta e com os textos da tela.
                    val dm = resources.displayMetrics
                    val black = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
                    black.eraseColor(Color.BLACK)
                    processBitmap(black, mode, pkg, cardRect, acc, maxOf(err, 1))
                }
            } else {
                processBitmap(bmp, mode, pkg, cardRect, acc, 0)
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
        // Só conta mudança grande (um card aparecendo); mapa mexendo não dispara leitura.
        return changed >= px.size / 50
    }

    private fun overlapsCard(w: OcrWord, r: Rect): Boolean =
        r.contains((w.left + w.right) / 2, (w.top + w.bottom) / 2)

    private fun processBitmap(bmp: Bitmap, mode: Int, pkg: String?, cardRect: Rect?, acc: List<OfferParser.TextItem>, blockedCode: Int) {
        val manual = mode != MODE_AUTO
        if (!manual && !screenChanged(bmp)) {
            bmp.recycle()
            finishCapture()
            return
        }
        if (blockedCode != 0) {
            // Print bloqueado: não há o que ler na foto preta.
            handleOcr(bmp, mode, pkg, cardRect, acc, OcrResult(emptyList(), emptyList()), blockedCode)
            return
        }
        OcrReader.readFull(bmp) { res -> handleOcr(bmp, mode, pkg, cardRect, acc, res, 0) }
    }

    private fun handleOcr(bmp: Bitmap, mode: Int, pkg: String?, cardRect: Rect?, acc: List<OfferParser.TextItem>, res: OcrResult?, blockedCode: Int) {
        val manual = mode != MODE_AUTO
        try {
            if (res == null) {
                if (manual) {
                    toast("Falha ao ler o print. Guardado em Artefatos")
                    ArtifactStore.save(this, bmp, ArtifactStore.KIND_NOT_FOUND, "Falha na leitura do texto (OCR)", null, null, pkg, acc)
                }
                return
            }
            val words = if (cardRect == null) res.words else res.words.filterNot { overlapsCard(it, cardRect) }
            val lines = if (cardRect == null) res.lines else res.lines.filterNot {
                it.top >= cardRect.top - 6 && it.bottom <= cardRect.bottom + 6
            }
            val region = modelsFor(pkg)
            val texts = textModelsFor(pkg)
            // Só o que foi treinado vale: texto da tela, texto da foto e áreas da imagem.
            val trained = readTrained(region, texts, acc, words, lines, bmp.width, bmp.height)
            if (mode == MODE_CALC) {
                DebugLog.recordManual(lines, if (trained != null) 1 else 0)
            } else if (mode == MODE_AUTO) {
                DebugLog.recordOcr(lines, if (trained != null) 1 else 0)
            }
            val shot = mode == MODE_SHOT
            val prefix = (if (blockedCode != 0) "App bloqueou o print (código $blockedCode): tela preta, só os textos da tela. " else "") +
                (if (shot) "Print real. " else "")
            if (trained != null) {
                if (mode == MODE_CALC) lastOfferKey = ""
                if (!shot) publishOffer(trained.offer)
                if (shot || (mode == MODE_CALC && ArtifactStore.keepSuccess(this))) {
                    val p = trained.offer
                    val status = prefix + "Leu " + OfferCalculator.money(p.amount) + " | " +
                        OfferCalculator.decimal(p.km) + " km | " + p.minutes.toInt() + " min (" + trained.name + ")"
                    ArtifactStore.save(this, bmp, ArtifactStore.KIND_READ, status, res.words, modelReadings(region, texts, acc, words, lines, bmp.width, bmp.height), pkg, acc)
                    if (shot) toast("Print guardado em Artefatos")
                }
            } else if (manual) {
                // Não achou card: o artefato diz isso claramente, com tudo que foi lido na tela.
                val why = if (region.isEmpty() && texts.isEmpty()) {
                    "Sem treino para este app. Nenhum card gerado"
                } else {
                    "Nenhum card de corrida encontrado pelo treino"
                }
                ArtifactStore.save(
                    this, bmp, ArtifactStore.KIND_NOT_FOUND, prefix + why,
                    res.words, modelReadings(region, texts, acc, words, lines, bmp.width, bmp.height), pkg, acc
                )
                toast(if (shot) "Print guardado em Artefatos. $why" else "$why. Print e leitura guardados em Artefatos")
            } else {
                // A oferta saiu da tela: some o card e libera a mesma oferta para o futuro.
                lastOfferKey = ""
                ui?.hideCard()
                ui?.hideLine()
            }
        } finally {
            bmp.recycle()
            finishCapture()
        }
    }

    // Ordem: texto da tela, texto da foto, áreas da imagem. O primeiro que ler valor e KM válidos vale.
    private fun readTrained(
        region: List<Template>,
        texts: List<TextModel>,
        acc: List<OfferParser.TextItem>,
        words: List<OcrWord>,
        lines: List<OfferParser.TextItem>,
        w: Int,
        h: Int
    ): Trained? {
        for (m in texts) {
            val o = TextReader.read(m, if (m.src == TextSrc.ACC) acc else lines).offer
            if (o != null) return Trained("texto ${m.name}", o)
        }
        for (t in region) {
            val o = TemplateReader.read(t, words, w, h).offer
            if (o != null) return Trained("modelo ${t.name}", o)
        }
        return null
    }

    // O que cada modelo leu e onde procurou, para comparar com o print depois.
    private fun modelReadings(
        region: List<Template>,
        texts: List<TextModel>,
        acc: List<OfferParser.TextItem>,
        words: List<OcrWord>,
        lines: List<OfferParser.TextItem>,
        w: Int,
        h: Int
    ): JSONArray {
        val arr = JSONArray()
        for (m in texts) {
            val r = TextReader.read(m, if (m.src == TextSrc.ACC) acc else lines)
            val o = JSONObject()
            o.put("nome", m.name + " (" + TextSrc.label(m.src) + ")")
            o.put("ok", r.offer != null)
            val campos = JSONObject()
            for ((f, text) in r.raw) campos.put(f.label, text)
            o.put("campos", campos)
            arr.put(o)
        }
        for (t in region) {
            val r = TemplateReader.read(t, words, w, h)
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

    private fun publishOffer(offer: OfferParser.ParsedOffer) {
        val key = "${offer.amount}|${offer.km}|${offer.minutes}"
        // Mesma corrida lida de novo (ou de outra fonte): não pisca outro card.
        if (key == lastOfferKey) return
        val cfg = AppConfig.load(this)
        val result = OfferCalculator.calculate(offer.amount, offer.km, offer.minutes, cfg) ?: return
        lastOfferKey = key
        ui?.showCard(result, cfg.cardSeconds)
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
