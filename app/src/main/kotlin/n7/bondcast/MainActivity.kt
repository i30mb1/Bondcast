package n7.bondcast

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import n7.bondcast.settings.StreamSettings
import n7.bondcast.ui.SettingsScreen
import n7.bondcast.ui.StreamScreen

internal class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = appGraph()
        val launchIntent = intent
        val autostart = launchIntent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true
        val hasSeed = launchIntent != null && hasSeedExtras(launchIntent)
        if (hasSeed && launchIntent != null) {
            lifecycleScope.launch {
                val current = graph.settingsRepository.settings.first()
                graph.settingsRepository.save(applySeedExtras(current, launchIntent))
                if (autostart) graph.streamController.start()
            }
        }
        setContent {
            AppTheme {
                App(graph = graph, autostart = autostart && !hasSeed)
            }
        }
    }

    override fun onDestroy() {
        val controller = appGraph().streamController
        if (isFinishing && !controller.isSessionActive) controller.close()
        super.onDestroy()
    }

    internal companion object {
        /** adb shell am start -n n7.bondcast/.MainActivity --ez autostart true */
        const val EXTRA_AUTOSTART: String = "autostart"

        /**
         * Отладочная заливка настроек с CLI перед autostart, например:
         * adb shell am start -n n7.bondcast/.MainActivity --ez autostart true \
         *   --ez cfg_bonding true --es cfg_srtla_host 192.168.31.133 --ei cfg_srtla_port 5000
         */
        private val SEED_KEYS = listOf(
            "cfg_host",
            "cfg_port",
            "cfg_stream_name",
            "cfg_bonding",
            "cfg_srtla_host",
            "cfg_srtla_port",
        )

        private fun hasSeedExtras(intent: Intent): Boolean = SEED_KEYS.any(intent::hasExtra)

        private fun applySeedExtras(current: StreamSettings, intent: Intent): StreamSettings = current.copy(
            host = intent.getStringExtra("cfg_host") ?: current.host,
            port = intent.getIntExtra("cfg_port", current.port),
            streamName = intent.getStringExtra("cfg_stream_name") ?: current.streamName,
            bondingEnabled = intent.getBooleanExtra("cfg_bonding", current.bondingEnabled),
            srtlaHost = intent.getStringExtra("cfg_srtla_host") ?: current.srtlaHost,
            srtlaPort = intent.getIntExtra("cfg_srtla_port", current.srtlaPort),
        )
    }
}

private val requiredPermissions = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
)

@Composable
private fun App(graph: AppGraph, autostart: Boolean) {
    val context = LocalContext.current

    fun requiredGranted(): Boolean = requiredPermissions.all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(requiredGranted()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = requiredGranted() }
    val allPermissions = requiredPermissions + Manifest.permission.POST_NOTIFICATIONS

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(allPermissions)
    }

    if (!granted) {
        PermissionScreen(onRequest = { launcher.launch(allPermissions) })
        return
    }

    val settings by graph.settingsRepository.settings.collectAsState(initial = null)
    var showSettings by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // настройки удобнее крутить в портрете, стрим живёт в ландшафте (манифест: sensorLandscape)
    LaunchedEffect(showSettings) {
        (context as? Activity)?.requestedOrientation = if (showSettings) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    LaunchedEffect(autostart) {
        if (autostart && !graph.streamController.isSessionActive) {
            graph.streamController.start()
        }
    }

    val currentSettings = settings
    if (showSettings && currentSettings != null) {
        SettingsScreen(
            initial = currentSettings,
            onSave = { new ->
                scope.launch { graph.settingsRepository.save(new) }
                showSettings = false
            },
            onBack = { showSettings = false },
        )
    } else {
        StreamScreen(
            controller = graph.streamController,
            settings = currentSettings,
            onOpenSettings = { showSettings = true },
            onUpdateSettings = { new -> scope.launch { graph.settingsRepository.save(new) } },
            thermalMonitor = graph.thermalMonitor,
            mitigations = graph.thermalMitigations,
            obsController = graph.obsController,
        )
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Для стрима нужны камера и микрофон")
            Button(onClick = onRequest) {
                Text("Выдать разрешения")
            }
        }
    }
}
