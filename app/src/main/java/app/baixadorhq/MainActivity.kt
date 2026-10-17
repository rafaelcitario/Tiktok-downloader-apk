package app.baixadorhq

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {

    // Sobe sempre que a tela volta ao primeiro plano, para reavaliar as permissões.
    private var tick by mutableIntStateOf(0)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        Notifier(this).ensureChannels()
        if (Prefs(this).monitoring) {
            try {
                WatcherService.start(this)
            } catch (e: Exception) {
                // Será tentado de novo na próxima vez que o app abrir.
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && !notificationsGranted(this)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AppTheme {
                MainScreen(
                    tick = tick,
                    onPasteClick = { pasteAndDownload() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }

    /** O app está em primeiro plano aqui, então o Android deixa ler a área de transferência. */
    private fun pasteAndDownload() {
        val link = ClipboardWatcher.readLink(this)
        if (link == null) {
            Toast.makeText(this, "Nenhum link do TikTok na área de transferência", Toast.LENGTH_LONG).show()
            return
        }
        try {
            LinkHandler.startDownload(this, link, audio = false)
            Toast.makeText(this, "Baixando em alta qualidade…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Não consegui iniciar o download: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
