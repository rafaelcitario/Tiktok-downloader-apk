package app.baixadorhq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Religa o monitoramento quando o celular liga (ou depois de atualizar o app). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val prefs = Prefs(context)
        if (prefs.monitoring && prefs.startOnBoot) {
            try {
                WatcherService.start(context)
            } catch (e: Exception) {
                // Sem permissão para iniciar agora; o app volta a ligar quando for aberto.
            }
        }
    }
}
