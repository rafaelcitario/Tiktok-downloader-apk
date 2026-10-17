package app.baixadorhq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat

/** Todas as notificações do app: serviço fixo, pergunta (pop-up), progresso, concluído e erro. */
class Notifier(private val context: Context) {

    private val nm: NotificationManager = context.getSystemService(NotificationManager::class.java)

    init {
        // Idempotente. Garante os canais mesmo quando o sistema acorda o app só para mostrar o pop-up.
        ensureChannels()
    }

    fun ensureChannels() {
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Monitoramento", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantém o app ativo para perceber links copiados"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PROMPT, "Pergunta de download", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Aparece quando você copia um link de vídeo do TikTok"
                enableVibration(true)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Downloads em andamento", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Downloads concluídos", NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** Notificação fixa do serviço que roda 24h. */
    fun serviceNotification(): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), FLAGS,
        )
        val paste = PendingIntent.getActivity(
            context, 1,
            Intent(context, PasteDownloadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            FLAGS,
        )
        return NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Baixador HQ está ativo")
            .setContentText("Copie um link do TikTok. Se nada aparecer, toque em “Colar e baixar”.")
            .setContentIntent(open)
            .addAction(0, "Colar e baixar", paste)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * O pop-up: tocar baixa; arrastar para o lado descarta (comportamento padrão do Android).
     * A notificação não some sozinha ao tocar: o serviço a substitui pelo progresso (mesmo id).
     */
    fun showPrompt(url: String, alreadyDownloaded: Boolean) {
        val title = if (alreadyDownloaded) {
            "Você já baixou este vídeo. Baixar de novo?"
        } else {
            "Baixar este vídeo em alta qualidade?"
        }
        val notification = NotificationCompat.Builder(context, CH_PROMPT)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(shorten(url))
            .setStyle(NotificationCompat.BigTextStyle().bigText(url))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setContentIntent(servicePending(WatcherService.ACTION_DOWNLOAD, url))
            .addAction(0, "Só o áudio", servicePending(WatcherService.ACTION_DOWNLOAD_AUDIO, url))
            .setAutoCancel(false)
            .setTimeoutAfter(10 * 60 * 1000L)
            .build()
        nm.notify(idFor(url), notification)
    }

    fun showProgress(url: String, percent: Int) {
        val notification = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Baixando vídeo…")
            .setContentText(if (percent >= 0) "$percent%" else "Preparando…")
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(idFor(url), notification)
    }

    fun showDone(url: String, saved: Downloader.Saved, title: String) {
        val id = idFor(url)
        val mime = if (saved.isAudio) "audio/mpeg" else "video/mp4"

        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(saved.uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, saved.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        val viewPending = PendingIntent.getActivity(context, id, view, FLAGS)
        val sharePending = PendingIntent.getActivity(
            context, id, Intent.createChooser(send, "Compartilhar"), FLAGS,
        )

        val notification = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(if (saved.isAudio) "Áudio salvo" else "Vídeo salvo em alta qualidade")
            .setContentText(title.ifBlank { saved.displayName })
            .setContentIntent(viewPending)
            .addAction(0, "Compartilhar", sharePending)
            .setAutoCancel(true)
            .build()
        nm.notify(id, notification)
    }

    fun showError(url: String, message: String) {
        val notification = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Não foi possível baixar")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .addAction(0, "Tentar de novo", servicePending(WatcherService.ACTION_DOWNLOAD, url))
            .setAutoCancel(true)
            .build()
        nm.notify(idFor(url), notification)
    }

    private fun servicePending(action: String, url: String): PendingIntent {
        val intent = Intent(context, WatcherService::class.java)
            .setAction(action)
            // O "data" faz cada par (ação, link) virar um PendingIntent diferente.
            .setData(Uri.parse(url))
            .putExtra(WatcherService.EXTRA_URL, url)
        return PendingIntent.getForegroundService(context, 0, intent, FLAGS)
    }

    private fun idFor(url: String): Int = 1000 + (url.hashCode() and 0xFFFFF)

    private fun shorten(url: String): String =
        url.removePrefix("https://").removePrefix("http://").take(60)

    companion object {
        private const val CH_SERVICE = "service"
        private const val CH_PROMPT = "prompt"
        private const val CH_PROGRESS = "progress"
        private const val CH_DONE = "done"
        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
