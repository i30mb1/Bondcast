package n7.bondcast.chat.twitch

import org.json.JSONObject

/** Желаемая подписка EventSub — хранится в реестре и переигрывается на реконнекте. */
internal data class EventSubSubscription(
    val type: String,
    val version: String,
    val condition: JSONObject,
) {
    /** Ключ для дедупа реестра (JSONObject не сравнивается по значению). */
    val key: String get() = "$type:$version:$condition"
}

/** Входящее уведомление: тип подписки + сырое тело события. */
internal data class EventSubNotification(
    val type: String,
    val event: JSONObject,
)
