package app.baixadorhq

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock

/**
 * Observa a área de transferência e avisa quando aparece um link novo do TikTok.
 *
 * Importante: do Android 10 em diante o sistema só entrega a área de transferência a quem tem foco
 * na tela (ou é o teclado padrão). Em segundo plano, [check] devolve [Result.NO_ACCESS]; o serviço de
 * acessibilidade resolve isso ganhando foco por uma fração de segundo (ver ClipboardAccessibilityService).
 */
class ClipboardWatcher(
    private val context: Context,
    private val onLink: (String) -> Unit,
) {
    enum class Result {
        /** Achou um link novo do TikTok e já avisou. */
        FOUND,

        /** Conseguiu olhar, mas não há nada novo (mesma cópia de antes, cópia antiga ou não é link do TikTok). */
        NOTHING_NEW,

        /** O Android não deixou olhar agora (app sem foco). */
        NO_ACCESS,
    }

    private val cm: ClipboardManager = context.getSystemService(ClipboardManager::class.java)
    private var started = false

    private val listener = ClipboardManager.OnPrimaryClipChangedListener { check() }

    /** Liga o aviso automático do sistema (só chega quando o Android permite, ex.: app em primeiro plano). */
    fun start() {
        if (started) return
        cm.addPrimaryClipChangedListener(listener)
        started = true
    }

    fun stop() {
        if (!started) return
        cm.removePrimaryClipChangedListener(listener)
        started = false
    }

    /** Lê o conteúdo só se a cópia for nova (evita o aviso "colou da área de transferência" à toa). */
    fun check(): Result {
        val description: ClipDescription? = try {
            cm.primaryClipDescription
        } catch (e: Exception) {
            null
        }
        if (description == null) return Result.NO_ACCESS
        if (!description.hasMimeType("text/*")) return Result.NOTHING_NEW

        val stamp = description.timestamp
        synchronized(LOCK) {
            if (stamp == lastStamp) return Result.NOTHING_NEW
            lastStamp = stamp
        }
        // Uma cópia de dez minutos atrás não é "o link que acabei de copiar".
        if (!isFresh(stamp)) return Result.NOTHING_NEW

        val link = readLink(context) ?: return Result.NOTHING_NEW
        onLink(link)
        return Result.FOUND
    }

    companion object {
        private val LOCK = Any()
        private const val FRESH_MS = 120_000L

        // Compartilhado: o serviço em primeiro plano e o de acessibilidade não leem a mesma cópia duas vezes.
        private var lastStamp = 0L

        private fun isFresh(stamp: Long): Boolean {
            if (stamp <= 0L) return true // sem data confiável: o LinkHandler evita avisar em dobro
            val range = -5_000L..FRESH_MS
            return (System.currentTimeMillis() - stamp) in range ||
                (SystemClock.elapsedRealtime() - stamp) in range
        }

        /** Lê agora o link do TikTok da área de transferência (precisa de foco ou permissão do sistema). */
        fun readLink(context: Context): String? {
            return try {
                val cm = context.getSystemService(ClipboardManager::class.java)
                val clip = cm.primaryClip
                if (clip == null || clip.itemCount == 0 || !clip.description.hasMimeType("text/*")) {
                    null
                } else {
                    TikTokLink.extract(clip.getItemAt(0).coerceToText(context))
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
