package app.baixadorhq

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat

/** Decide o que fazer quando um link do TikTok é detectado. */
object LinkHandler {
    private var lastLink: String? = null
    private var lastAt = 0L

    /** Evita avisar duas vezes o mesmo link (o serviço e a acessibilidade podem detectar juntos). */
    @Synchronized
    private fun isDuplicate(link: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (link == lastLink && now - lastAt < 90_000) return true
        lastLink = link
        lastAt = now
        return false
    }

    /** Link detectado na área de transferência: pergunta (pop-up) ou baixa direto, conforme a opção. */
    fun onClipboardLink(context: Context, link: String) {
        val app = context.applicationContext
        val prefs = Prefs(app)
        if (!prefs.monitoring) return
        if (isDuplicate(link)) return

        if (prefs.autoDownload) {
            try {
                startDownload(app, link, audio = false)
                return
            } catch (e: Exception) {
                // Não deu para iniciar o serviço agora: cai no pop-up de pergunta.
            }
        }
        Notifier(app).showPrompt(link, History(app).hasUrl(link))
    }

    /** Pede ao serviço para baixar (vídeo ou só o áudio). */
    fun startDownload(context: Context, link: String, audio: Boolean) {
        val intent = Intent(context, WatcherService::class.java)
            .setAction(if (audio) WatcherService.ACTION_DOWNLOAD_AUDIO else WatcherService.ACTION_DOWNLOAD)
            .putExtra(WatcherService.EXTRA_URL, link)
        ContextCompat.startForegroundService(context, intent)
    }
}
