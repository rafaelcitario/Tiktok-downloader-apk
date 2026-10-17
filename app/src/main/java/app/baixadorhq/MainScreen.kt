package app.baixadorhq

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun MainScreen(tick: Int, onPasteClick: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val history = remember { History(context) }

    val version by History.version.collectAsState()
    val entries = remember(version) { history.all() }

    var monitoring by remember { mutableStateOf(prefs.monitoring) }
    var autoDownload by remember { mutableStateOf(prefs.autoDownload) }
    var wifiOnly by remember { mutableStateOf(prefs.wifiOnly) }
    var startOnBoot by remember { mutableStateOf(prefs.startOnBoot) }

    // Reavaliados toda vez que a tela volta ao primeiro plano (tick muda).
    val notificationsOk = remember(tick) { notificationsGranted(context) }
    val batteryOk = remember(tick) { batteryUnrestricted(context) }
    val accessibilityOk = remember(tick) { accessibilityEnabled(context) }
    val diagnostic = remember(tick) { prefs.diagnostic }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Text("Baixador HQ", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Copie o link de um vídeo do TikTok e eu pergunto se você quer baixar em alta qualidade.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SwitchRow(
                            title = "Monitorar links copiados",
                            subtitle = "Mantém o app ativo 24h em segundo plano",
                            checked = monitoring,
                            onChecked = { on ->
                                monitoring = on
                                prefs.monitoring = on
                                try {
                                    if (on) WatcherService.start(context) else WatcherService.stop(context)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Não foi possível iniciar o serviço agora", Toast.LENGTH_LONG).show()
                                }
                            },
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("Para funcionar 24h", style = MaterialTheme.typography.titleLarge)

                        StatusRow(
                            ok = notificationsOk,
                            title = "Notificações",
                            hint = "Sem isso o pop-up de download não aparece.",
                            actionLabel = "Permitir",
                            onAction = {
                                safeStart(
                                    context,
                                    Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                                )
                            },
                        )

                        StatusRow(
                            ok = batteryOk,
                            title = "Bateria sem restrições",
                            hint = "Evita que o Android pause o app. Na Samsung, confira também em Configurações > " +
                                "Bateria > Limites de uso em segundo plano > Apps que nunca entram em suspensão.",
                            actionLabel = "Ajustar",
                            onAction = { requestBatteryExemption(context) },
                        )

                        StatusRow(
                            ok = accessibilityOk,
                            title = "Serviço de acessibilidade (recomendado)",
                            hint = "É ele que faz o pop-up aparecer quando você toca em \"Copiar link\" no TikTok, mesmo com " +
                                "o app fechado: o Android só deixa ler a área de transferência de quem está na tela, " +
                                "então o serviço abre uma janela invisível por uma fração de segundo. Ele só recebe " +
                                "eventos do TikTok, não lê a tela e não guarda nada. O Android pode mostrar \"colou da " +
                                "área de transferência\": é normal. Se aparecer \"configuração restrita\": " +
                                "Info do app > menu ⋮ > Permitir configurações restritas.",
                            actionLabel = "Ativar",
                            onAction = {
                                safeStart(context, Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                            },
                        )
                        if (accessibilityOk && diagnostic.isNotBlank()) {
                            Text("Último evento: $diagnostic", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!accessibilityOk) {
                            TextButton(onClick = {
                                safeStart(
                                    context,
                                    Intent(
                                        AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }) { Text("Abrir info do app") }
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Preferências", style = MaterialTheme.typography.titleLarge)
                        SwitchRow(
                            title = "Baixar sem perguntar",
                            subtitle = "Ao copiar o link, baixa direto (sem pop-up)",
                            checked = autoDownload,
                            onChecked = { autoDownload = it; prefs.autoDownload = it },
                        )
                        SwitchRow(
                            title = "Somente em Wi-Fi",
                            subtitle = "Não gasta seu plano de dados",
                            checked = wifiOnly,
                            onChecked = { wifiOnly = it; prefs.wifiOnly = it },
                        )
                        SwitchRow(
                            title = "Iniciar com o celular",
                            subtitle = "Religa o monitoramento depois de reiniciar",
                            checked = startOnBoot,
                            onChecked = { startOnBoot = it; prefs.startOnBoot = it },
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Baixar agora", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = onPasteClick, modifier = Modifier.fillMaxWidth()) {
                            Text("Colar link e baixar")
                        }
                        Text(
                            "Outras formas: no TikTok, Compartilhar > Baixador HQ; o botão \"Colar e baixar\" da " +
                                "notificação fixa; ou o atalho \"Baixar link\" no painel rápido (arraste o painel e " +
                                "toque no lápis para adicionar).",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item {
                Text("Histórico", style = MaterialTheme.typography.titleLarge)
            }

            if (entries.isEmpty()) {
                item {
                    Text("Nada baixado ainda.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                items(entries, key = { it.uri + it.time }) { entry ->
                    HistoryRow(
                        entry = entry,
                        onOpen = { openMedia(context, entry) },
                        onDelete = { deleteEntry(context, history, entry) },
                    )
                }
            }

            item {
                Text(
                    "Baixe apenas vídeos seus ou que o criador permite baixar.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun StatusRow(
    ok: Boolean,
    title: String,
    hint: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (ok) "✅" else "⚠️", modifier = Modifier.padding(end = 8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (!ok) {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
        if (!ok) {
            Text(hint, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun HistoryRow(entry: History.Entry, onOpen: () -> Unit, onDelete: () -> Unit) {
    val kind = if (entry.audio) "Áudio" else "Vídeo"
    val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.time))
    val details = listOf(kind, entry.author, date).filter { it.isNotBlank() }.joinToString(" · ")

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                entry.title.ifBlank { "Vídeo do TikTok" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(details, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = onOpen) { Text("Abrir") }
                TextButton(onClick = onDelete) { Text("Excluir") }
            }
        }
    }
}

// ---------- auxiliares ----------

fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun batteryUnrestricted(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java) ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun accessibilityEnabled(context: Context): Boolean {
    val enabled = AndroidSettings.Secure.getString(
        context.contentResolver,
        AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val component = ComponentName(context, ClipboardAccessibilityService::class.java)
    val full = component.flattenToString()
    val short = component.flattenToShortString()
    return enabled.split(':').any { it.equals(full, ignoreCase = true) || it.equals(short, ignoreCase = true) }
}

private fun requestBatteryExemption(context: Context) {
    val direct = Intent(
        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    if (!safeStart(context, direct)) {
        safeStart(context, Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun safeStart(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent)
    true
} catch (e: Exception) {
    false
}

private fun openMedia(context: Context, entry: History.Entry) {
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(entry.uri), if (entry.audio) "audio/mpeg" else "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Nenhum app encontrado para abrir o arquivo", Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Não foi possível abrir o arquivo", Toast.LENGTH_LONG).show()
    }
}

private fun deleteEntry(context: Context, history: History, entry: History.Entry) {
    try {
        context.contentResolver.delete(Uri.parse(entry.uri), null, null)
    } catch (e: Exception) {
        // Arquivo já removido ou sem permissão: tira só do histórico.
    }
    history.remove(entry)
}
