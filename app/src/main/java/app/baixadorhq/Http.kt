package app.baixadorhq

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; SM-A556E) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"

    /** Usado ao falar direto com o site do TikTok (a página do vídeo e o arquivo vêm com o mesmo "navegador"). */
    const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /**
     * Igual ao [client], mas guarda os cookies em memória. O TikTok só libera o arquivo do vídeo
     * para quem apresenta os cookies recebidos ao abrir a página dele.
     */
    val webClient: OkHttpClient by lazy {
        client.newBuilder().cookieJar(MemoryCookieJar()).build()
    }
}

private class MemoryCookieJar : CookieJar {
    private val store = CopyOnWriteArrayList<Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            store.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            store.add(cookie)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return store.filter { it.expiresAt > now && it.matches(url) }
    }
}

object Net {
    fun isOnWifi(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
