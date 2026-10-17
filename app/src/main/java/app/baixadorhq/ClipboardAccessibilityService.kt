package app.baixadorhq

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Serviço de acessibilidade OPCIONAL que faz o aviso funcionar com o app em segundo plano.
 *
 * O problema: desde o Android 10 só o app com foco na tela pode ler a área de transferência.
 * A solução: quando você toca em "Copiar link" no TikTok, este serviço abre por uma fração de segundo
 * uma janela invisível de 1 pixel que ganha o foco, lê a cópia e a fecha na hora.
 *
 * Privacidade:
 *  - Só recebe eventos do app do TikTok (ver accessibility_service_config.xml).
 *  - Não lê o conteúdo da tela (canRetrieveWindowContent = false). Olha só o nome do botão tocado
 *    (ex.: "Copiar link"), na memória, para saber quando conferir a cópia. Nada é guardado ou enviado.
 *  - Não toca em nada na tela.
 */
class ClipboardAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(this) }
    private var watcher: ClipboardWatcher? = null

    // Plano de conferências: instantes (ms depois do gatilho) em que olhamos a cópia.
    // O TikTok às vezes gera o link curto pela internet, então o link novo pode demorar a aparecer.
    private var plan = LongArray(0)
    private var step = 0
    private var generation = 0
    private var lastWeakAt = 0L
    private var strongPlan = false
    private var sawNoAccess = false

    private var overlay: View? = null
    private var probeTimeout: Runnable? = null

    private val attempt = Runnable { runAttempt() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        watcher = ClipboardWatcher(this) { link -> LinkHandler.onClipboardLink(this, link) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !prefs.monitoring) return
        when (event.eventType) {
            // Gatilho forte: tocou em "Copiar link" (ou o TikTok avisou "Link copiado").
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED,
            -> if (looksLikeCopyLink(event)) startPlan(STRONG_PLAN)

            // Gatilho fraco, caso o nome do botão não bata: uma conferência quando o menu fecha.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val now = SystemClock.elapsedRealtime()
                if (plan.isEmpty() && now - lastWeakAt > WEAK_COOLDOWN_MS) {
                    lastWeakAt = now
                    startPlan(WEAK_PLAN)
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        generation++
        mainHandler.removeCallbacks(attempt)
        probeTimeout?.let { mainHandler.removeCallbacks(it) }
        probeTimeout = null
        removeOverlay()
        plan = LongArray(0)
        watcher = null
    }

    // ---------- plano de conferências ----------

    private fun startPlan(newPlan: LongArray) {
        generation++
        mainHandler.removeCallbacks(attempt)
        plan = newPlan
        step = 0
        strongPlan = newPlan === STRONG_PLAN
        sawNoAccess = false
        if (strongPlan) note("toque em \"Copiar link\" detectado no TikTok.")
        mainHandler.postDelayed(attempt, plan[0])
    }

    private fun stopPlan() {
        mainHandler.removeCallbacks(attempt)
        plan = LongArray(0)
        step = 0
    }

    /** Termina o plano e deixa um recado na tela inicial do app (ajuda a entender o que aconteceu). */
    private fun finishPlan(found: Boolean) {
        val message = when {
            found -> "link lido e pop-up enviado."
            sawNoAccess -> "o Android não liberou a área de transferência em segundo plano."
            strongPlan -> "conferi a cópia, mas não havia link novo do TikTok."
            else -> null
        }
        if (message != null) note(message)
        stopPlan()
    }

    private fun note(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        prefs.diagnostic = "$time, $message"
    }

    private fun nextStep() {
        step++
        if (step < plan.size) {
            mainHandler.postDelayed(attempt, plan[step] - plan[step - 1])
        } else {
            finishPlan(false)
        }
    }

    private fun runAttempt() {
        val w = watcher ?: return
        val gen = generation
        when (w.check()) {
            ClipboardWatcher.Result.FOUND -> finishPlan(true)
            ClipboardWatcher.Result.NOTHING_NEW -> nextStep()
            ClipboardWatcher.Result.NO_ACCESS -> readWithFocus { result ->
                if (gen != generation) return@readWithFocus // chegou um gatilho novo no meio do caminho
                when (result) {
                    ClipboardWatcher.Result.FOUND -> finishPlan(true)
                    ClipboardWatcher.Result.NOTHING_NEW -> nextStep()
                    ClipboardWatcher.Result.NO_ACCESS -> {
                        sawNoAccess = true
                        nextStep()
                    }
                }
            }
        }
    }

    // ---------- janela de 1 pixel que ganha o foco ----------

    private fun readWithFocus(onResult: (ClipboardWatcher.Result) -> Unit) {
        val noAccess = ClipboardWatcher.Result.NO_ACCESS
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        if (wm == null || overlay != null) {
            onResult(noAccess)
            return
        }

        var handled = false
        val view = object : View(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (!hasWindowFocus || handled) return
                handled = true
                probeTimeout?.let { mainHandler.removeCallbacks(it) }
                // Posta: deixa o sistema terminar de registrar o foco antes de ler.
                mainHandler.post {
                    val result = watcher?.check() ?: noAccess
                    removeOverlay()
                    onResult(result)
                }
            }
        }

        // Sem FLAG_NOT_FOCUSABLE de propósito: precisa poder receber o foco.
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }

        try {
            wm.addView(view, params)
        } catch (e: Exception) {
            onResult(noAccess)
            return
        }
        overlay = view

        val timeout = Runnable {
            if (!handled) {
                handled = true
                removeOverlay()
                onResult(noAccess)
            }
        }
        probeTimeout = timeout
        mainHandler.postDelayed(timeout, PROBE_TIMEOUT_MS)
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeViewImmediate(view)
        } catch (e: Exception) {
            // Já removida.
        }
    }

    // ---------- reconhecer "Copiar link" ----------

    private fun looksLikeCopyLink(event: AccessibilityEvent): Boolean {
        val label = StringBuilder()
        event.text?.forEach { label.append(it).append(' ') }
        event.contentDescription?.let { label.append(it) }
        if (label.isBlank() || label.length > 120) return false // texto longo não é botão

        val text = label.toString().lowercase(Locale.ROOT)
        return COPY_WORDS.any { text.contains(it) } && LINK_WORDS.any { text.contains(it) }
    }

    companion object {
        private val STRONG_PLAN = longArrayOf(500, 1300, 2600)
        private val WEAK_PLAN = longArrayOf(900)
        private const val WEAK_COOLDOWN_MS = 6_000L
        private const val PROBE_TIMEOUT_MS = 800L

        private val COPY_WORDS = listOf(
            "copiar", "copiad", "copia", "copy", "copied", "copie", "kopier", "kopyala", "salin",
            "копир", "复制", "複製", "コピー", "복사",
        )
        private val LINK_WORDS = listOf(
            "link", "enlace", "lien", "bağlantı", "tautan", "ссылк", "链接", "連結", "リンク", "링크",
        )
    }
}
