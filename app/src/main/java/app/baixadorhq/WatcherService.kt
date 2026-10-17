package app.baixadorhq

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Serviço em primeiro plano que fica ativo 24h: observa a área de transferência e executa os downloads.
 */
class WatcherService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifier: Notifier
    private lateinit var prefs: Prefs
    private lateinit var history: History
    private var watcher: ClipboardWatcher? = null
    private val active: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        prefs = Prefs(this)
        history = History(this)
        notifier.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        ensureWatcher()

        val url = intent?.getStringExtra(EXTRA_URL)
        when (intent?.action) {
            ACTION_DOWNLOAD -> if (url != null) download(url, audio = false)
            ACTION_DOWNLOAD_AUDIO -> if (url != null) download(url, audio = true)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watcher?.stop()
        watcher = null
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        val notification = notifier.serviceNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            // O sistema pode recusar se o serviço foi iniciado em segundo plano; segue como serviço comum.
        }
    }

    private fun ensureWatcher() {
        if (prefs.monitoring && watcher == null) {
            watcher = ClipboardWatcher(this) { link -> LinkHandler.onClipboardLink(this, link) }
                .also { it.start() }
        }
    }

    private fun download(url: String, audio: Boolean) {
        val key = "$url|$audio"
        if (!active.add(key)) return

        scope.launch {
            val wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "baixadorhq:download")
            wakeLock.acquire(10 * 60 * 1000L)
            try {
                if (prefs.wifiOnly && !Net.isOnWifi(this@WatcherService)) {
                    notifier.showError(url, "A opção “Somente Wi-Fi” está ligada e você está nos dados móveis.")
                    return@launch
                }
                notifier.showProgress(url, -1)

                val result = withContext(Dispatchers.IO) { fetch(url, audio) }

                history.add(
                    History.Entry(
                        url = url,
                        title = result.info.title,
                        author = result.info.author,
                        uri = result.saved.uri.toString(),
                        audio = audio,
                        time = System.currentTimeMillis(),
                    ),
                )
                notifier.showDone(url, result.saved, result.info.title)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                notifier.showError(url, friendlyMessage(t))
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                active.remove(key)
                // Se o monitoramento está desligado, o serviço só existia para este download.
                if (active.isEmpty() && !prefs.monitoring) stopSelf()
            }
        }
    }

    private class Fetched(val info: VideoInfo, val saved: Downloader.Saved)

    private fun fetch(url: String, audio: Boolean): Fetched {
        val info = TikTokResolver.resolve(url)
        val source = (if (audio) info.audioUrl else info.videoUrl)
            ?: throw ResolveException(
                when {
                    audio -> "Este vídeo não tem áudio disponível."
                    info.isSlideshow -> "Este post é um carrossel de fotos, não um vídeo. Use “Só o áudio” se quiser a música."
                    else -> "Não encontrei o arquivo de vídeo."
                },
            )

        var lastShown = -1
        val saved = Downloader(applicationContext).download(
            url = source,
            baseName = Downloader.baseName(info),
            audio = audio,
            referer = info.referer,
            withCookies = info.needsCookies,
        ) { percent ->
            if (percent >= lastShown + 5 || percent == 100) {
                lastShown = percent
                notifier.showProgress(url, percent)
            }
        }
        return Fetched(info, saved)
    }

    private fun friendlyMessage(t: Throwable): String = when (t) {
        is ResolveException, is DownloadException -> t.message ?: "Não foi possível obter o vídeo."
        is UnknownHostException, is SocketTimeoutException, is ConnectException ->
            "Sem conexão com a internet. Tente de novo."
        is IOException -> "A conexão caiu durante o download. Tente de novo."
        else -> "Falha ao baixar (${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""})."
    }

    companion object {
        const val ACTION_DOWNLOAD = "app.baixadorhq.action.DOWNLOAD"
        const val ACTION_DOWNLOAD_AUDIO = "app.baixadorhq.action.DOWNLOAD_AUDIO"
        const val EXTRA_URL = "url"
        private const val NOTIF_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WatcherService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WatcherService::class.java))
        }
    }
}
