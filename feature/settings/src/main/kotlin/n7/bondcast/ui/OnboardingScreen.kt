package n7.bondcast.ui

import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import n7.bondcast.DiscordColors
import n7.bondcast.qr.QrPayload
import n7.bondcast.settings.StreamSettings
import n7.bondcast.ui.components.DiscordField
import n7.bondcast.ui.components.DiscordHint
import n7.bondcast.ui.components.DiscordSwitchRow
import n7.bondcast.ui.components.RowDivider
import n7.bondcast.ui.components.SectionLabel
import n7.bondcast.ui.components.SettingsCard

@Composable
public fun OnboardingScreen(
    initial: StreamSettings,
    onFinish: (StreamSettings) -> Unit,
) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

    var host by remember { mutableStateOf(initial.srtlaHost.ifBlank { initial.obsHost.ifBlank { initial.host.takeIf { it != "10.0.2.2" } ?: "" } }) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var srtlaPort by remember { mutableStateOf(initial.srtlaPort.toString()) }
    var streamName by remember { mutableStateOf(initial.streamName) }
    var passphrase by remember { mutableStateOf(initial.passphrase) }
    var bonding by remember { mutableStateOf(initial.bondingEnabled) }
    var obsEnabled by remember { mutableStateOf(initial.obsEnabled) }
    var obsPort by remember { mutableStateOf(initial.obsPort.toString()) }
    var obsPassword by remember { mutableStateOf(initial.obsPassword) }
    var showScanner by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val portInt = port.toIntOrNull()
    val srtlaPortInt = srtlaPort.toIntOrNull()
    val obsPortInt = obsPort.toIntOrNull()
    val portValid = portInt != null && portInt in 1..65535
    val srtlaPortValid = srtlaPortInt != null && srtlaPortInt in 1..65535
    val activePortValid = if (bonding) srtlaPortValid else portValid
    val valid = host.isNotBlank() && streamName.isNotBlank() && activePortValid

    if (showScanner) {
        QrScannerScreen(
            onResult = { payload ->
                when (payload) {
                    is QrPayload.ObsConnect -> {
                        host = payload.host
                        obsPort = payload.port.toString()
                        obsPassword = payload.password
                        obsEnabled = true
                    }

                    is QrPayload.ServerConfig -> {
                        (payload.host ?: payload.srtlaHost)?.let { host = it }
                        payload.port?.let { port = it.toString() }
                        payload.srtlaPort?.let { srtlaPort = it.toString() }
                        payload.streamName?.let { streamName = it }
                        payload.passphrase?.let { passphrase = it }
                        payload.bonding?.let { bonding = it }
                        payload.obsPort?.let {
                            obsPort = it.toString()
                            obsEnabled = true
                        }
                        payload.obsPassword?.let {
                            obsPassword = it
                            obsEnabled = true
                        }
                    }

                    is QrPayload.Unknown ->
                        Toast.makeText(context, "QR не распознан", Toast.LENGTH_SHORT).show()
                }
                showScanner = false
            },
            onBack = { showScanner = false },
        )
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Bondcast",
                color = DiscordColors.textPrimary,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 24.dp),
            )
            DiscordHint("Укажи, куда отправлять эфир — свой сервер, облако или отсканируй QR. Остальное уже настроено, поправишь потом в настройках.")

            SectionLabel("Куда стримить")
            SettingsCard {
                Button(
                    onClick = { showScanner = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("📷 Сканировать QR")
                }
                RowDivider()
                DiscordSwitchRow(
                    label = "Объединить сети (SIM + Wi-Fi)",
                    checked = bonding,
                    onCheckedChange = { bonding = it },
                    info = "Шлём эфир по всем сетям сразу — если одна прилегла, тащат остальные. " +
                        "Надёжнее в движении. Видео уходит на srtla_rec, порт справа поменяется.",
                )
                RowDivider()
                Row {
                    DiscordField(
                        label = "Хост сервера",
                        value = host,
                        onValueChange = { host = it },
                        keyboardType = KeyboardType.Decimal,
                        isError = host.isBlank(),
                        modifier = Modifier.weight(2f),
                        info = "IP компа в локалке (ipconfig → IPv4) или адрес облака (belabox, IRLToolkit).",
                    )
                    DiscordField(
                        label = "Порт",
                        value = if (bonding) srtlaPort else port,
                        onValueChange = { if (bonding) srtlaPort = it else port = it },
                        keyboardType = KeyboardType.Number,
                        isError = !activePortValid,
                        modifier = Modifier.weight(1f),
                    )
                }
                RowDivider()
                DiscordField(
                    label = "Имя стрима",
                    value = streamName,
                    onValueChange = { streamName = it },
                    isError = streamName.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    info = "Поток приедет на сервер как live/<имя>. Латиницей, без пробелов.",
                )
            }

            Button(
                onClick = {
                    onFinish(
                        initial.copy(
                            host = host.trim(),
                            port = portInt ?: initial.port,
                            streamName = streamName.trim(),
                            passphrase = passphrase,
                            bondingEnabled = bonding,
                            srtlaHost = host.trim(),
                            srtlaPort = srtlaPortInt ?: initial.srtlaPort,
                            obsEnabled = obsEnabled,
                            obsHost = host.trim(),
                            obsPort = obsPortInt ?: initial.obsPort,
                            obsPassword = obsPassword,
                            onboardingCompleted = true,
                        ),
                    )
                },
                enabled = valid,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp),
            ) {
                Text("Готово")
            }
        }
    }
}
