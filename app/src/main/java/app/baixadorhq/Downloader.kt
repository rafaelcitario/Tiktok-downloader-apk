package app.baixadorhq

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import okhttp3.Request
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Erro com mensagem já escrita para a pessoa (aparece como está na notificação). */
class DownloadException(message: String) : IOException(message)

/** Baixa o arquivo e grava direto na galeria (Filmes/Baixador HQ ou Música/Baixador HQ). */
class Downloader(private val context: Context) {

    class Saved(val uri: Uri, val displayName: String, val isAudio: Boolean)

    fun download(
        url: String,
        baseName: String,
        audio: Boolean,
        referer: String,
        withCookies: Boolean,
        onProgress: (Int) -> Unit,
    ): Saved {
        val client = if (withCookies) Http.webClient else Http.client
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", if (withCookies) Http.WEB_USER_AGENT else Http.USER_AGENT)
            .header("Referer", referer)
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DownloadException("O download falhou (erro ${response.code}).")
            }
            val body = response.body ?: throw DownloadException("Resposta vazia do servidor.")

            // Se vier uma página (bloqueio, captcha) em vez do arquivo, não salva lixo na galeria.
            val type = body.contentType()?.toString()?.lowercase(Locale.ROOT).orEmpty()
            if (type.startsWith("text/") || type.contains("json")) {
                throw DownloadException("O servidor não entregou o arquivo. Tente de novo em instantes.")
            }

            val mime = when {
                !audio -> "video/mp4"
                type.contains("mp4") || type.contains("m4a") || type.contains("aac") -> "audio/mp4"
                else -> "audio/mpeg"
            }
            val extension = when (mime) {
                "video/mp4" -> "mp4"
                "audio/mp4" -> "m4a"
                else -> "mp3"
            }
            save(body.byteStream(), body.contentLength(), "$baseName.$extension", mime, audio, onProgress)
        }
    }

    private fun save(
        input: java.io.InputStream,
        total: Long,
        name: String,
        mime: String,
        audio: Boolean,
        onProgress: (Int) -> Unit,
    ): Saved {
        val resolver = context.contentResolver
        val collection = if (audio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val folder = if (audio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/Baixador HQ")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw DownloadException("Não foi possível criar o arquivo na galeria.")

        try {
            var done = 0L
            val out = resolver.openOutputStream(uri)
                ?: throw DownloadException("Não foi possível gravar o arquivo.")
            out.use { sink ->
                input.use { source ->
                    val buffer = ByteArray(64 * 1024)
                    var lastPercent = -1
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        done += read
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }
            if (done < MIN_BYTES) throw DownloadException("O arquivo veio vazio. Tente de novo.")

            val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, ready, null, null)
            return Saved(uri, name, audio)
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Exception) {
            }
            throw t
        }
    }

    companion object {
        private const val MIN_BYTES = 2048L

        /** Nome do arquivo: título limpo + data/hora, para nunca repetir. */
        fun baseName(info: VideoInfo): String {
            val clean = info.title
                .replace(Regex("[^\\p{L}\\p{N} _-]"), "")
                .trim()
                .take(50)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            return if (clean.isBlank()) "tiktok_$stamp" else "${clean}_$stamp"
        }
    }
}
