package n7.bondcast

import android.Manifest
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
import kotlinx.coroutines.launch
import n7.bondcast.ui.SettingsScreen
import n7.bondcast.ui.StreamScreen

internal class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val autostart = intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true
        setContent {
            AppTheme {
                App(graph = appGraph(), autostart = autostart)
            }
        }
    }

    internal companion object {
        /** adb shell am start -n n7.bondcast/.MainActivity --ez autostart true */
        const val EXTRA_AUTOSTART: String = "autostart"
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
