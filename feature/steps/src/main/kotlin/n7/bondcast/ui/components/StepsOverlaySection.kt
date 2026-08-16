package n7.bondcast.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import n7.bondcast.DiscordColors
import n7.bondcast.feature.steps.R
import n7.bondcast.ui.street.StreetChip
import n7.bondcast.ui.street.StreetIconChip
import n7.bondcast.ui.street.StreetSection
import n7.bondcast.ui.street.streetBody

/**
 * Блок шагомера в окне оверлеев: включить, выключить, обнулить — три кнопки в одном ряду.
 *
 * Самого числа тут нет: оно и так висит в кадре, а окно должно оставаться компактным — это лишь
 * пульт для оверлея, а не второй экран с показаниями.
 *
 * Разрешение [Manifest.permission.ACTIVITY_RECOGNITION] просим здесь же, при включении — до этого
 * момента шаги никому не нужны, и лишний системный диалог на старте приложения незачем.
 */
@Composable
public fun StepsOverlaySection(
    enabled: Boolean,
    sensorAvailable: Boolean,
    onEnabled: (Boolean) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // до Android 10 распознавание активности разрешения не требовало
    val permissionNeeded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    var granted by remember {
        mutableStateOf(
            !permissionNeeded ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        granted = allowed
        // включаем только если пользователь согласился — иначе рамка висела бы с нулём
        if (allowed) onEnabled(true)
    }

    // подтверждение обнуления: кнопка необратимая, а окно открывают прямо в эфире — случайный тап
    // стёр бы накопленное за стрим число. Первый тап подсвечивает кнопку, второй обнуляет
    var confirming by remember { mutableStateOf(false) }
    LaunchedEffect(confirming) {
        if (!confirming) return@LaunchedEffect
        delay(CONFIRM_TIMEOUT_MS)
        confirming = false
    }

    StreetSection(
        title = stringResource(R.string.steps_section_title),
        modifier = modifier,
        leading = { StepsIcon(color = DiscordColors.accent) },
        info = stringResource(R.string.steps_info_main),
    ) {
        if (!sensorAvailable) {
            Text(
                text = stringResource(R.string.steps_no_sensor),
                color = DiscordColors.textSecondary,
                style = streetBody,
            )
            return@StreetSection
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            StreetChip(stringResource(R.string.steps_burn_in_on), enabled, Modifier.weight(1f)) {
                if (granted) onEnabled(true) else launcher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
            }
            StreetChip(stringResource(R.string.steps_burn_in_off), !enabled, Modifier.weight(1f)) { onEnabled(false) }
            StreetIconChip(
                selected = confirming,
                onClick = {
                    if (confirming) {
                        onReset()
                        confirming = false
                    } else {
                        confirming = true
                    }
                },
            ) { color -> RefreshIcon(color = color) }
        }

        val hint = when {
            confirming -> stringResource(R.string.steps_reset_confirm)
            !granted -> stringResource(R.string.steps_permission_hint)
            enabled -> stringResource(R.string.steps_drag_hint)
            else -> null
        }
        if (hint != null) {
            Text(
                text = hint,
                color = if (confirming) DiscordColors.accent else DiscordColors.textSecondary,
                style = streetBody,
            )
        }
    }
}

private const val CONFIRM_TIMEOUT_MS = 3000L
