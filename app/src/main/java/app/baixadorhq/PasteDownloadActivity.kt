package app.baixadorhq

import android.app.Activity
import android.widget.Toast

/**
 * Activity invisível aberta pelo botão "Colar e baixar" (notificação fixa e atalho do painel rápido).
 * Ao ganhar o foco, o Android permite ler a área de transferência; baixa o link e fecha na hora.
 */
class PasteDownloadActivity : Activity() {
    private var handled = false

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        val link = ClipboardWatcher.readLink(this)
        if (link == null) {
            Toast.makeText(this, "Nenhum link do TikTok na área de transferência", Toast.LENGTH_LONG).show()
        } else {
            try {
                LinkHandler.startDownload(this, link, audio = false)
                Toast.makeText(this, "Baixando em alta qualidade…", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Não consegui iniciar o download: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        finish()
    }
}
