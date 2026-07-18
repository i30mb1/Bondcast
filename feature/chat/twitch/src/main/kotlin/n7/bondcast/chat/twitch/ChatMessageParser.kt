package n7.bondcast.chat.twitch

import n7.bondcast.chat.ChatAuthor
import n7.bondcast.chat.ChatBadge
import n7.bondcast.chat.ChatEvent
import n7.bondcast.chat.ChatPlatform
import n7.bondcast.chat.ChatRole
import n7.bondcast.chat.MessageFragment
import org.json.JSONArray
import org.json.JSONObject

/** Разбор тела channel.chat.message в доменное [ChatEvent.Message]. */
internal object ChatMessageParser {

    fun message(event: JSONObject, badgeUrls: Map<String, String> = emptyMap()): ChatEvent.Message {
        val badges = parseBadges(event.optJSONArray("badges"), badgeUrls)
        val roles = badges.mapNotNull { roleFor(it.setId) }.toSet()
        val name = event.optString("chatter_user_name").ifBlank { event.optString("chatter_user_login") }
        val message = event.getJSONObject("message")
        return ChatEvent.Message(
            platform = ChatPlatform.TWITCH,
            id = event.getString("message_id"),
            author = ChatAuthor(
                id = event.getString("chatter_user_id"),
                displayName = name,
                color = parseColor(event.optString("color")),
                roles = roles,
                badges = badges,
            ),
            fragments = parseFragments(message.optJSONArray("fragments"), message.optString("text")),
            timestampMs = System.currentTimeMillis(),
            replyToId = event.optJSONObject("reply")?.optString("parent_message_id")?.ifBlank { null },
        )
    }

    private fun parseFragments(fragments: JSONArray?, fallback: String): List<MessageFragment> {
        if (fragments == null || fragments.length() == 0) return listOf(MessageFragment.Text(fallback))
        return buildList {
            for (i in 0 until fragments.length()) {
                val fragment = fragments.getJSONObject(i)
                val text = fragment.optString("text")
                when (fragment.optString("type")) {
                    "emote" -> {
                        val emote = fragment.getJSONObject("emote")
                        val id = emote.getString("id")
                        add(MessageFragment.Emote(id = id, name = text, imageUrl = emoteUrl(id)))
                    }

                    "mention" -> {
                        val mention = fragment.getJSONObject("mention")
                        add(
                            MessageFragment.Mention(
                                userId = mention.optString("user_id").ifBlank { null },
                                name = mention.optString("user_name").ifBlank { mention.optString("user_login") },
                            ),
                        )
                    }

                    else -> add(MessageFragment.Text(text))
                }
            }
        }
    }

    private fun parseBadges(badges: JSONArray?, badgeUrls: Map<String, String>): List<ChatBadge> {
        if (badges == null) return emptyList()
        return buildList {
            for (i in 0 until badges.length()) {
                val badge = badges.getJSONObject(i)
                val setId = badge.getString("set_id")
                val version = badge.optString("id")
                add(
                    ChatBadge(
                        setId = setId,
                        version = version.ifBlank { null },
                        imageUrl = badgeUrls["$setId/$version"],
                    ),
                )
            }
        }
    }

    private fun parseColor(hex: String): Int? {
        val cleaned = hex.removePrefix("#")
        if (cleaned.length != 6) return null
        return runCatching { (0xFF shl 24) or cleaned.toInt(16) }.getOrNull()
    }

    private fun roleFor(setId: String): ChatRole? = when (setId) {
        "broadcaster" -> ChatRole.OWNER
        "moderator" -> ChatRole.MODERATOR
        "staff", "admin", "global_mod" -> ChatRole.STAFF
        "founder" -> ChatRole.FOUNDER
        "vip" -> ChatRole.VIP
        "subscriber" -> ChatRole.SUBSCRIBER
        "partner" -> ChatRole.VERIFIED
        else -> null
    }

    private fun emoteUrl(id: String): String = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/1.0"
}
