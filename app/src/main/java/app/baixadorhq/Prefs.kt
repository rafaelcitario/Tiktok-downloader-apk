package app.baixadorhq

import android.content.Context

/** Preferências simples do app (SharedPreferences). */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Serviço de monitoramento ligado (roda 24h em segundo plano). */
    var monitoring: Boolean
        get() = sp.getBoolean("monitoring", true)
        set(value) {
            sp.edit().putBoolean("monitoring", value).apply()
        }

    /** Baixar direto, sem perguntar pela notificação. */
    var autoDownload: Boolean
        get() = sp.getBoolean("autoDownload", false)
        set(value) {
            sp.edit().putBoolean("autoDownload", value).apply()
        }

    /** Só baixar em Wi-Fi (poupa o plano de dados). */
    var wifiOnly: Boolean
        get() = sp.getBoolean("wifiOnly", false)
        set(value) {
            sp.edit().putBoolean("wifiOnly", value).apply()
        }

    /** Último evento do serviço de acessibilidade, mostrado na tela inicial para entender por que o pop-up não apareceu. */
    var diagnostic: String
        get() = sp.getString("diagnostic", "") ?: ""
        set(value) {
            sp.edit().putString("diagnostic", value).apply()
        }

    /** Iniciar o monitoramento quando o celular ligar. */
    var startOnBoot: Boolean
        get() = sp.getBoolean("startOnBoot", true)
        set(value) {
            sp.edit().putBoolean("startOnBoot", value).apply()
        }
}
