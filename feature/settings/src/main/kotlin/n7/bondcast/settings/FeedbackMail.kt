package n7.bondcast.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import n7.bondcast.feature.settings.R
import n7.bondcast.logging.deviceSummary
import n7.bondcast.logging.logFileUri
import java.io.File

private const val TAG = "Feedback"

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
 *
 * **Почему не селектор.** Хрестоматийный рецепт «ACTION_SEND + selector(ACTION_SENDTO, mailto:)»
 * на живом устройстве (Nothing, Android 16) не резолвится вообще: замер изнутри процесса дал
 * `queryIntentActivities` = 1 для голого селектора и 0 для той же пары вместе — одинаково с
 * вложением и без. Поэтому почтовики ищем сами запросом по `SENDTO`+`mailto:` (на него
 * отвечают именно почтовые клиенты, а не мессенджеры), а письмо отправляем обычным
 * `ACTION_SEND`, прибитым к найденному пакету — так доезжает и вложение.
 *
 * @return null, если почтовика на устройстве действительно нет.
 */
internal fun feedbackIntent(
    context: Context,
    message: String,
    settings: StreamSettings,
    log: File?,
): Intent? {
    val base = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_feedback_subject))
        // ровно то, что человек написал: любой наш довесок в этом поле он читает как чужой текст
        putExtra(Intent.EXTRA_TEXT, message.trim())
        if (log != null) {
            val uri = logFileUri(context, log)
            putExtra(Intent.EXTRA_STREAM, uri)
            // FLAG_GRANT_READ_URI_PERMISSION раздаёт права по data/clipData, а не по EXTRA_STREAM
            clipData = ClipData.newRawUri(SHARE_LABEL, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    val mailApps = mailPackages(context)
    if (mailApps.isEmpty()) {
        Log.w(TAG, "почтовых клиентов нет: на SENDTO+mailto не ответил ни один пакет")
        return null
    }
    Log.i(TAG, "почтовики: ${mailApps.joinToString()}")
    val perApp = mailApps.map { pkg -> Intent(base).setPackage(pkg) }
    return if (perApp.size == 1) {
        perApp.single()
    } else {
        // несколько почтовиков — выбор ровно из них, без мессенджеров и Bluetooth,
        // которые полезли бы в обычный чузер по text/plain
        Intent.createChooser(perApp.first(), context.getString(R.string.settings_feedback_chooser_title))
            .putExtra(Intent.EXTRA_INITIAL_INTENTS, perApp.drop(1).toTypedArray())
    }
}

/**
 * Пакеты почтовых клиентов. Работает только вместе с объявлением `<queries>` в манифесте:
 * без него список пуст, сколько бы почтовиков ни стояло.
 */
private fun mailPackages(context: Context): List<String> = runCatching {
    context.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0)
        .map { it.activityInfo.packageName }
        .distinct()
}.getOrDefault(emptyList())

/** Шапка вложенного лога: версия, устройство, настройки эфира. В тело письма не идёт. */
internal fun diagnostics(context: Context, settings: StreamSettings): String = buildString {
    appendLine(deviceSummary(context))
    append(settings.summaryForMail())
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
