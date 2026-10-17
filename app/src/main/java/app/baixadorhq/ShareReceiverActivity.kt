package app.baixadorhq

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Aparece em "Compartilhar" do TikTok. Como a pessoa escolheu o app de propósito, baixa direto,
 * sem perguntar de novo. Funciona em qualquer versão do Android, sem depender da área de transferência.
 */
class ShareReceiverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val link = TikTokLink.extract(intent.getStringExtra(Intent.EXTRA_TEXT))
        if (link == null) {
            Toast.makeText(this, "Não encontrei um link do TikTok nesse conteúdo", Toast.LENGTH_LONG).show()
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
