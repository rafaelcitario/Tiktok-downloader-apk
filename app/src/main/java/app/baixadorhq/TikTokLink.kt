package app.baixadorhq

import java.util.Locale

/** Encontra um link de vídeo do TikTok dentro de um texto qualquer. */
object TikTokLink {
    // Aceita tiktok.com, www., vm., vt., m. e o formato curto /t/XXXX
    private val regex = Regex(
        """https?://(?:[a-z0-9-]+\.)?tiktok\.com/\S+""",
        RegexOption.IGNORE_CASE,
    )

    fun extract(text: CharSequence?): String? {
        if (text.isNullOrBlank()) return null
        for (match in regex.findAll(text)) {
            val link = match.value.trimEnd('.', ',', ';', ')', ']', '"', '\'', '!', '?')
            if (isVideoLink(link)) return link
        }
        return null
    }

    /** Descarta perfis, hashtags e lives: só links de vídeo/foto ou os encurtados (vm./vt.) interessam. */
    private fun isVideoLink(link: String): Boolean {
        val lower = link.lowercase(Locale.ROOT)
        val host = lower.substringAfter("://").substringBefore('/')
        if (host.startsWith("vm.") || host.startsWith("vt.") || host.startsWith("vn.")) return true
        return listOf("/video/", "/photo/", "/t/", "/v/").any { lower.contains(it) }
    }
}
