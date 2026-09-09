package n7.bondcast.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import n7.bondcast.feature.settings.R
import n7.bondcast.logging.deviceSummary
import n7.bondcast.logging.logFileUri
import java.io.File

/** Ярлык вложения в ClipData — виден в некоторых почтовиках. */
private const val SHARE_LABEL = "bondcast-log"

/** Куда уходят пожелания. */
public const val FEEDBACK_EMAIL: String = "fate.i30mb1@gmail.com"

/**
 * Значения из настроек, которых в письме быть не должно.
 *
 * Адреса и порты сюда НЕ входят — без них разбирать проблемы с подключением бессмысленно.
 */
internal fun StreamSettings.secretsForLog(): List<String> =
    listOf(passphrase, twitchStreamKey, obsPassword)

/**
 * Письмо с пожеланием: получатель, тема, текст и вложенный лог уже подставлены — человеку
 * остаётся нажать «Отправить» в своём почтовике.
 *
 * Отправляем через почтовый клиент, а не своим запросом: у проекта нет центрального сервера
 * (панель живёт на машине пользователя), а зашивать SMTP-пароль в APK нельзя. Заодно человек
 * видит письмо целиком до отправки и приходит оно с его настоящего адреса — можно ответить.
 */
internal fun feedbackIntent(
    context: Context,
    message: String,
    settings: StreamSettings,
    log: File?,
): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_feedback_subject))
    putExtra(Intent.EXTRA_TEXT, feedbackBody(context, message, settings))
    if (log != null) {
        val uri = logFileUri(context, log)
        putExtra(Intent.EXTRA_STREAM, uri)
        // FLAG_GRANT_READ_URI_PERMISSION раздаёт права по data/clipData, а не по EXTRA_STREAM:
        // систему учили переносить одно в другое внутри startActivity, но лучше не полагаться
        // на эту деталь — иначе почтовик получит уri, который не сможет прочитать
        clipData = ClipData.newRawUri(SHARE_LABEL, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    // селектор оставляет в выборе только почтовики: без него в чузер лезут мессенджеры,
    // а адрес получателя они игнорируют
    selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
}

private fun feedbackBody(context: Context, message: String, settings: StreamSettings): String = buildString {
    appendLine(message.trim())
    appendLine()
    appendLine("---")
    appendLine(deviceSummary(context))
    appendLine(settings.summaryForMail())
}

/** Настройки эфира одной строкой — половина вопросов «а как у тебя настроено» отпадает сразу. */
private fun StreamSettings.summaryForMail(): String = buildString {
    appendLine("видео: ${width}x$height@$fps, ${videoCodec.name}, $videoBitrateKbps кбит/с" + if (abrEnabled) ", ABR" else "")
    appendLine("задержка SRT: $latencyMs мс")
    val target = when {
        twitchDirectEnabled -> "Twitch напрямую"
        bondingEnabled -> "свой сервер, бондинг SRTLA"
        else -> "свой сервер, SRT"
    }
    append("куда: $target")
}
