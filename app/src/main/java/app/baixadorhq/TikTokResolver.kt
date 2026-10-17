package app.baixadorhq

import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

data class VideoInfo(
    val title: String,
    val author: String,
    val videoUrl: String?,
    val audioUrl: String?,
    val isSlideshow: Boolean,
    /** Endereço enviado como "Referer" ao baixar o arquivo. */
    val referer: String,
    /** Se o arquivo só é liberado com os cookies da página do TikTok (fonte direta). */
    val needsCookies: Boolean,
)

class ResolveException(message: String) : Exception(message)

/**
 * Descobre o arquivo de vídeo (sem marca d'água, na maior qualidade que existir) a partir do link do TikTok.
 *
 * Duas fontes, nesta ordem:
 *  1. tikwm.com (serviço público, não oficial): devolve o vídeo HD sem marca d'água.
 *  2. A própria página do vídeo no TikTok: usada se a primeira falhar ou estiver fora do ar.
 *
 * Nenhuma das duas é uma API oficial, então o TikTok pode mudar algo a qualquer momento.
 * Tudo que depende delas está concentrado neste arquivo.
 */
object TikTokResolver {
    private const val TIKWM_API = "https://www.tikwm.com/api/"
    private const val TIKWM_HOME = "https://www.tikwm.com/"
    private const val TIKTOK_HOME = "https://www.tiktok.com/"

    fun resolve(link: String): VideoInfo {
        var first: Exception? = null
        try {
            return fromTikwm(link)
        } catch (e: IOException) {
            first = e
        } catch (e: ResolveException) {
            first = e
        }

        var second: Exception? = null
        try {
            return fromTikTokPage(link)
        } catch (e: IOException) {
            second = e
        } catch (e: ResolveException) {
            second = e
        }

        // Sem internet nas duas: deixa o aviso de conexão. Senão, mostra o motivo mais útil.
        if (first is IOException && second is IOException) throw first
        if (first is ResolveException) throw first
        if (second is ResolveException) throw second
        throw ResolveException("Não foi possível obter o vídeo agora.")
    }

    // ---------- Fonte 1: tikwm.com ----------

    private fun fromTikwm(link: String): VideoInfo {
        var attempt = 0
        while (true) {
            try {
                return requestTikwm(link)
            } catch (e: ResolveException) {
                // O serviço gratuito aceita 1 pedido por segundo: espera um pouco e tenta de novo uma vez.
                val limited = e.message?.contains("limit", ignoreCase = true) == true
                if (attempt == 0 && limited) {
                    attempt++
                    Thread.sleep(1300)
                    continue
                }
                throw e
            }
        }
    }

    private fun requestTikwm(link: String): VideoInfo {
        val form = FormBody.Builder()
            .add("url", link)
            .add("hd", "1")
            .build()
        val request = Request.Builder()
            .url(TIKWM_API)
            .header("User-Agent", Http.USER_AGENT)
            .post(form)
            .build()

        return Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ResolveException("O serviço de download respondeu com erro ${response.code}.")
            }
            val raw = response.body?.string().orEmpty()
            val json = try {
                JSONObject(raw)
            } catch (e: Exception) {
                throw ResolveException("Resposta inesperada do serviço de download.")
            }
            if (json.optInt("code", -1) != 0) {
                throw ResolveException(json.text("msg").ifBlank { "Não foi possível obter este vídeo." })
            }
            val data = json.optJSONObject("data")
                ?: throw ResolveException("Resposta sem dados do vídeo.")

            val hd = data.text("hdplay").asUrl()
            val sd = data.text("play").asUrl()
            val withMark = data.text("wmplay").asUrl()
            val music = data.text("music").asUrl()
                ?: data.optJSONObject("music_info")?.text("play")?.asUrl()
            val photoCount = data.optJSONArray("images")?.length() ?: 0
            val author = data.optJSONObject("author")
                ?.let { it.text("nickname").ifBlank { it.text("unique_id") } }
                .orEmpty()

            VideoInfo(
                title = data.text("title"),
                author = author,
                // Em carrossel de fotos não existe arquivo de vídeo de verdade.
                videoUrl = if (photoCount > 0) null else (hd ?: sd ?: withMark),
                audioUrl = music,
                isSlideshow = photoCount > 0,
                referer = TIKWM_HOME,
                needsCookies = false,
            )
        }
    }

    // ---------- Fonte 2: página do vídeo no TikTok ----------

    private fun fromTikTokPage(link: String): VideoInfo {
        val request = Request.Builder()
            .url(link)
            .header("User-Agent", Http.WEB_USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val html = Http.webClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ResolveException("O TikTok respondeu com erro ${response.code}.")
            }
            response.body?.string().orEmpty()
        }

        val unreadable = "Não consegui ler a página do vídeo."
        val root = extractState(html) ?: throw ResolveException(unreadable)
        val detail = root.optJSONObject("__DEFAULT_SCOPE__")?.optJSONObject("webapp.video-detail")
            ?: throw ResolveException(unreadable)
        if (detail.optInt("statusCode", 0) != 0) {
            throw ResolveException("Este vídeo não está disponível (privado, removido ou restrito).")
        }
        val item = detail.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
            ?: throw ResolveException(unreadable)

        val isSlideshow = item.optJSONObject("imagePost") != null
        val videoUrl = if (isSlideshow) null else item.optJSONObject("video")?.let { pickBest(it) }
        val music = item.optJSONObject("music")?.text("playUrl")?.asUrl()
        val author = item.optJSONObject("author")
            ?.let { it.text("nickname").ifBlank { it.text("uniqueId") } }
            .orEmpty()

        return VideoInfo(
            title = item.text("desc"),
            author = author,
            videoUrl = videoUrl,
            audioUrl = music,
            isSlideshow = isSlideshow,
            referer = TIKTOK_HOME,
            needsCookies = true,
        )
    }

    /** A página traz todos os dados do vídeo em um JSON dentro de um <script>. */
    private fun extractState(html: String): JSONObject? {
        val marker = html.indexOf("__UNIVERSAL_DATA_FOR_REHYDRATION__")
        if (marker < 0) return null
        val open = html.indexOf('>', marker)
        if (open < 0) return null
        val close = html.indexOf("</script>", open)
        if (close < 0) return null
        return try {
            JSONObject(html.substring(open + 1, close))
        } catch (e: Exception) {
            null
        }
    }

    private class Candidate(val url: String, val pixels: Long, val bitrate: Long, val h264: Boolean)

    /** Escolhe a versão de maior resolução (empate: H.264, que abre em qualquer app; depois bitrate). */
    private fun pickBest(video: JSONObject): String? {
        val list = ArrayList<Candidate>()
        val infos = video.optJSONArray("bitrateInfo")
        if (infos != null) {
            for (i in 0 until infos.length()) {
                val info = infos.optJSONObject(i) ?: continue
                val play = info.optJSONObject("PlayAddr") ?: continue
                val url = firstHttp(play.optJSONArray("UrlList")) ?: continue
                list.add(
                    Candidate(
                        url = url,
                        pixels = play.optLong("Width") * play.optLong("Height"),
                        bitrate = info.optLong("Bitrate"),
                        h264 = info.optString("CodecType").contains("h264", ignoreCase = true),
                    ),
                )
            }
        }
        val best = list.maxWithOrNull(
            compareBy<Candidate>({ it.pixels }, { if (it.h264) 1 else 0 }, { it.bitrate }),
        )
        if (best != null) return best.url

        // Último recurso: endereços simples (o "downloadAddr" costuma vir com marca d'água).
        return video.optString("playAddr").takeIf { it.startsWith("http") }
            ?: video.optString("downloadAddr").takeIf { it.startsWith("http") }
    }

    private fun firstHttp(array: JSONArray?): String? {
        if (array == null) return null
        for (i in 0 until array.length()) {
            val s = array.optString(i)
            if (s.startsWith("http")) return s
        }
        return null
    }

    // ---------- auxiliares ----------

    private fun JSONObject.text(key: String): String =
        if (isNull(key)) "" else optString(key, "")

    private fun String.asUrl(): String? = when {
        isBlank() -> null
        startsWith("http") -> this
        startsWith("//") -> "https:$this"
        startsWith("/") -> "https://www.tikwm.com$this"
        else -> null
    }
}
