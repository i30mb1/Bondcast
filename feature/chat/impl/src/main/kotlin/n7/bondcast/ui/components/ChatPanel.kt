package n7.bondcast.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import n7.bondcast.DiscordColors
import n7.bondcast.settings.StreamSettings
import n7.bondcast.ui.street.StreetChip
import n7.bondcast.ui.street.StreetPanelScaffold
import n7.bondcast.ui.street.streetBody
import n7.bondcast.ui.street.streetLabel
import n7.bondcast.ui.street.upper

/**
 * Живое меню чата справа — на том же [StreetPanelScaffold] и в том же стиле, что другие
 * панели: street-лейблы + [StreetChip]-контролы. Пишет прямо в [StreamSettings] через
 * [onUpdate] — оверлей реагирует сразу. Ввод канала — окошком над клавиатурой.
 */
@Composable
public fun ChatPanel(
    settings: StreamSettings,
    onUpdate: (StreamSettings) -> Unit,
    twitchStatus: String,
    twitchLoggedIn: Boolean,
    onTwitchLogin: () -> Unit,
    onTwitchLogout: () -> Unit,
    onClose: () -> Unit,
) {
    var channelDialog by remember { mutableStateOf(false) }

    StreetPanelScaffold(title = "Чат", onClose = onClose) {
        Text(
            text = twitchStatus,
            color = DiscordColors.textSecondary,
            style = streetBody,
        )
        StreetChip(
            text = if (twitchLoggedIn) "Выйти из Twitch" else "Войти в Twitch",
            selected = twitchLoggedIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (twitchLoggedIn) onTwitchLogout() else onTwitchLogin()
        }

        ToggleRow("Показывать чат", settings.chatEnabled) { onUpdate(settings.copy(chatEnabled = it)) }
        ChannelRow(settings.chatChannel) { channelDialog = true }
        ToggleRow("Ники", settings.chatShowNicknames) { onUpdate(settings.copy(chatShowNicknames = it)) }
        ToggleRow("Значки", settings.chatShowBadges) { onUpdate(settings.copy(chatShowBadges = it)) }
        ToggleRow("Прятать команды", settings.chatHideCommands) { onUpdate(settings.copy(chatHideCommands = it)) }
        StepperRow("Шрифт", settings.chatFontSizeSp, "") {
            onUpdate(settings.copy(chatFontSizeSp = (settings.chatFontSizeSp + it).coerceIn(10, 40)))
        }
        StepperRow("Прозрачность", settings.chatOpacityPercent, "%", step = 5) {
            onUpdate(settings.copy(chatOpacityPercent = (settings.chatOpacityPercent + it).coerceIn(0, 100)))
        }
        StepperRow("Лимит", settings.chatMessageLimit, "", step = 5) {
            onUpdate(settings.copy(chatMessageLimit = (settings.chatMessageLimit + it).coerceIn(5, 200)))
        }
        ToggleRow("Гаснуть к верху", settings.chatFadeTopEnabled) { onUpdate(settings.copy(chatFadeTopEnabled = it)) }
        ToggleRow("Стирать старые (30с)", settings.chatAutoEraseEnabled) { onUpdate(settings.copy(chatAutoEraseEnabled = it)) }
    }

    if (channelDialog) {
        ChannelDialog(
            initial = settings.chatChannel,
            onConfirm = {
                onUpdate(settings.copy(chatChannel = it))
                channelDialog = false
            },
            onDismiss = { channelDialog = false },
        )
    }
}

@Composable
private fun RowLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.upper(),
        color = DiscordColors.accent,
        style = streetLabel,
        modifier = modifier,
    )
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RowLabel(label, Modifier.weight(1f))
        StreetChip("Вкл", value, Modifier.width(56.dp)) { onChange(true) }
        StreetChip("Выкл", !value, Modifier.width(56.dp)) { onChange(false) }
    }
}

@Composable
private fun StepperRow(label: String, value: Int, unit: String, step: Int = 1, onNudge: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RowLabel(label, Modifier.weight(1f))
        StreetChip("−", false, Modifier.width(38.dp)) { onNudge(-step) }
        Text(
            text = "$value$unit",
            color = DiscordColors.textSecondary,
            style = streetBody,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(40.dp),
        )
        StreetChip("+", false, Modifier.width(38.dp)) { onNudge(step) }
    }
}

@Composable
private fun ChannelRow(channel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        RowLabel("Канал", Modifier.weight(1f))
        Text(
            text = channel.ifBlank { "свой" },
            color = DiscordColors.textSecondary,
            style = streetBody,
        )
    }
}

// компактное окошко ввода: одна строка «Канал» + поле, поднято над клавиатурой (imePadding)
@Composable
private fun ChannelDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DiscordColors.panel)
                    .border(2.dp, DiscordColors.accent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = "КАНАЛ", color = DiscordColors.accent, style = streetLabel)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DiscordColors.inputBackground)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (text.isEmpty()) {
                            Text(
                                text = "свой канал",
                                color = DiscordColors.textMuted,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = DiscordColors.textPrimary),
                            cursorBrush = SolidColor(DiscordColors.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { onConfirm(text.trim()) }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus),
                        )
                    }
                    // крестик — очистить поле одним тапом
                    if (text.isNotEmpty()) {
                        Text(
                            text = "✕",
                            color = DiscordColors.textSecondary,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { text = "" }
                                .padding(start = 8.dp),
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}
