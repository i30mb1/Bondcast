package n7.bondcast.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import n7.bondcast.ButtonShape
import n7.bondcast.DiscordColors
import n7.bondcast.feature.settings.R
import n7.bondcast.logging.SessionLog
import n7.bondcast.settings.FEEDBACK_EMAIL
import n7.bondcast.settings.StreamSettings
import n7.bondcast.settings.diagnostics
import n7.bondcast.settings.feedbackIntent
import n7.bondcast.settings.secretsForLog
import n7.bondcast.ui.components.DiscordField
import n7.bondcast.ui.components.DiscordHint
import n7.bondcast.ui.components.DiscordSwitchRow
import n7.bondcast.ui.components.DiscordTopBar
import n7.bondcast.ui.components.RowDivider
import n7.bondcast.ui.components.SettingsCard

private const val TAG = "Feedback"

/** Чем закончилась попытка открыть почтовик — от этого зависит, что показать под кнопкой. */
private enum class SendFailure { NONE, NO_MAIL_APP, OTHER }

/**
 * Экран «Пожелания»: текст, тумблер вложения и отправка письмом.
 *
 * @param settings настройки эфира на момент открытия — уходят в письмо блоком диагностики.
 * @param sessionLog лог сеанса для вложения; null — прикладывать нечего, тумблер скрыт.
 */
@Composable
internal fun FeedbackScreen(
    settings: StreamSettings,
    sessionLog: SessionLog?,
    onBack: () -> Unit,
) {
    var message by remember { mutableStateOf("") }
    var attachLog by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf(SendFailure.NONE) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    fun send() {
        val text = message.trim()
        if (text.isBlank()) return
        failure = SendFailure.NONE
        coroutineScope.launch {
            // сборка дампа читает и переписывает файлы — не на главном потоке
            val log = if (attachLog && sessionLog != null) {
                withContext(Dispatchers.IO) {
                    sessionLog.dump(diagnostics(context, settings), settings.secretsForLog())
                }
            } else {
                null
            }
            // сборку и запуск держим врозь: раньше общий runCatching выдавал «нет почтовика»
            // на любую ошибку и прятал настоящую причину
            val built = runCatching { feedbackIntent(context, text, settings, log) }
            val intent = built.getOrNull()
            when {
                built.isFailure -> {
                    Log.w(TAG, "не собрался интент письма: ${built.exceptionOrNull()}")
                    failure = SendFailure.OTHER
                }

                intent == null -> failure = SendFailure.NO_MAIL_APP

                else -> try {
                    context.startActivity(intent)
                    message = ""
                } catch (e: ActivityNotFoundException) {
                    // почтовик нашёлся, но не запустился — это уже не «его нет»
                    Log.w(TAG, "почтовик не запустился: $e")
                    failure = SendFailure.OTHER
                }
            }
        }
    }

    fun copyEmail() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(FEEDBACK_EMAIL, FEEDBACK_EMAIL))
        Toast.makeText(context, R.string.settings_feedback_copied, Toast.LENGTH_SHORT).show()
    }

    BackHandler(onBack = onBack)

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            DiscordTopBar(
                title = stringResource(R.string.settings_feedback_title),
                onBack = onBack,
            )
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                SettingsCard {
                    DiscordField(
                        label = stringResource(R.string.settings_feedback_message_label),
                        value = message,
                        onValueChange = { message = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        placeholder = stringResource(R.string.settings_feedback_message_placeholder),
                        info = stringResource(R.string.settings_feedback_message_info),
                    )
                    if (sessionLog != null) {
                        RowDivider()
                        DiscordSwitchRow(
                            label = stringResource(R.string.settings_feedback_attach_log_label),
                            checked = attachLog,
                            onCheckedChange = { attachLog = it },
                            info = stringResource(R.string.settings_feedback_attach_log_info),
                        )
                    }
                    RowDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_feedback_send_button),
                            color = if (message.isBlank()) DiscordColors.textMuted else DiscordColors.blurple,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .clip(ButtonShape)
                                .clickable(enabled = message.isNotBlank(), onClick = ::send)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                when (failure) {
                    SendFailure.NO_MAIL_APP -> DiscordHint(
                        text = stringResource(R.string.settings_feedback_no_mail_app, FEEDBACK_EMAIL),
                        onClick = ::copyEmail,
                    )

                    SendFailure.OTHER -> DiscordHint(
                        text = stringResource(R.string.settings_feedback_send_failed, FEEDBACK_EMAIL),
                        onClick = ::copyEmail,
                    )

                    SendFailure.NONE -> Unit
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
