package n7.bondcast.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import n7.bondcast.DiscordColors
import n7.bondcast.feature.qr.R
import n7.bondcast.qr.BarcodeAnalyzer
import n7.bondcast.qr.QrPayload
import n7.bondcast.qr.qrPayloadParser
import n7.bondcast.ui.components.DiscordTopBar
import java.util.concurrent.Executors

@Composable
public fun QrScannerScreen(
    onResult: (QrPayload) -> Unit,
    onBack: () -> Unit,
) {
    LockScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val lifecycleOwner = remember(context) { context.findLifecycleOwner() }
    val parser = remember { qrPayloadParser() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var delivered by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var boundUseCases by remember { mutableStateOf<Array<UseCase>>(emptyArray()) }

    val cameraOpenError = stringResource(R.string.qr_scanner_camera_open_error)
    val cameraBusyError = stringResource(R.string.qr_scanner_camera_busy_error)

    LaunchedEffect(lifecycleOwner) {
        val cameraProvider = runCatching { ProcessCameraProvider.awaitInstance(context) }.getOrNull()
        if (cameraProvider == null) {
            error = cameraOpenError
            return@LaunchedEffect
        }
        provider = cameraProvider
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider { request -> surfaceRequest = request }
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply {
                setAnalyzer(
                    analysisExecutor,
                    BarcodeAnalyzer { raw ->
                        if (!delivered) {
                            delivered = true
                            onResult(parser.parse(raw))
                        }
                    },
                )
            }
        boundUseCases = arrayOf(preview, analysis)
        // без unbindAll(): чтобы не сорвать живой эфир, снимаем только свои use-case на выходе
        runCatching {
            cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }.onFailure { error = cameraBusyError }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (boundUseCases.isNotEmpty()) runCatching { provider?.unbind(*boundUseCases) }
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        surfaceRequest?.let { request ->
            CameraXViewfinder(surfaceRequest = request, modifier = Modifier.fillMaxSize())
        }

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(240.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(3.dp, DiscordColors.accent, RoundedCornerShape(20.dp)),
        )

        DiscordTopBar(title = stringResource(R.string.qr_scanner_title_label), onBack = onBack)

        val hint = error ?: stringResource(R.string.qr_scanner_hint_label)
        Text(
            text = hint,
            color = if (error != null) DiscordColors.accent else Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp),
        )
    }
}

private fun Context.findLifecycleOwner(): LifecycleOwner {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is LifecycleOwner) return current
        current = current.baseContext
    }
    error("Нет LifecycleOwner в контексте")
}
