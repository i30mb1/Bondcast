package n7.bondcast.logging

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Подкаталог кэша, который отдаёт FileProvider (см. res/xml/bondcast_log_paths.xml). */
internal const val SHARE_DIR_NAME = "share"

internal const val SHARE_FILE_NAME = "bondcast-log.txt"

private const val AUTHORITY_SUFFIX = ".logs"

/** Uri для вложения в письмо. Почтовик читает файл по нему, копировать никуда не нужно. */
public fun logFileUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)

/**
 * Шапка лога и одновременно блок диагностики в теле письма: без неё половина сообщений
 * превращается в переписку «а какой у тебя телефон и версия».
 */
public fun deviceSummary(context: Context): String = buildString {
    appendLine("Bondcast ${appVersion(context)}")
    appendLine("устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
    appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
    appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
    append("время: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
}

private fun appVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName} (${info.longVersionCode})"
}.getOrDefault("версия неизвестна")
