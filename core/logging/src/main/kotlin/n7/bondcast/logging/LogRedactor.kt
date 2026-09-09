package n7.bondcast.logging

/** Чем заменяем всё, что нельзя показывать. */
public const val REDACTED: String = "***"

/**
 * Короткий «секрет» вырезал бы куски обычных слов, а пустой — вообще всё подряд.
 * Четыре символа — порог, ниже которого пользовательские значения не осмысленны.
 */
private const val MIN_SECRET_LENGTH = 4

/**
 * Шаблоны на случай, если секрет не пришёл списком: токен мог протечь в лог чужой библиотеки
 * (StreamPack, OkHttp) в виде, которого нет в наших настройках.
 */
private val SECRET_PATTERNS = listOf(
    Regex("""oauth:[A-Za-z0-9._\-]{8,}""") to "oauth:$REDACTED",
    Regex("""Bearer\s+[A-Za-z0-9._\-]{8,}""") to "Bearer $REDACTED",
    Regex("""live_\d+_[A-Za-z0-9]{8,}""") to "live_$REDACTED",
)

/**
 * Готовит лог к отправке письмом.
 *
 * Адреса и порты НЕ трогаем: без них разбирать проблемы с подключением бессмысленно,
 * а сам адрес сервера пользователь и так сообщает в тексте пожелания.
 *
 * @param secrets значения из настроек, которых в письме быть не должно — ключ трансляции,
 *   passphrase SRT, пароль пульта OBS.
 */
public fun redactLog(text: String, secrets: List<String>): String {
    var result = text
    for (secret in secrets) {
        val trimmed = secret.trim()
        if (trimmed.length < MIN_SECRET_LENGTH) continue
        // literal-замена, а не регулярка: в passphrase легко встречаются . * [ ]
        result = result.replace(trimmed, REDACTED)
    }
    for ((pattern, replacement) in SECRET_PATTERNS) {
        result = result.replace(pattern, replacement)
    }
    return result
}
