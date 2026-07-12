package n7.bondcast.chat.twitch

import n7.bondcast.chat.ChatRole
import n7.bondcast.chat.MessageFragment
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatMessageParserTest {

    private val sample = """
        {
          "message_id": "abc-123",
          "broadcaster_user_id": "1",
          "chatter_user_id": "42",
          "chatter_user_login": "cool_user",
          "chatter_user_name": "Cool_User",
          "color": "#00FF7F",
          "badges": [
            { "set_id": "moderator", "id": "1" },
            { "set_id": "subscriber", "id": "12" }
          ],
          "message": {
            "text": "hello Kappa world",
            "fragments": [
              { "type": "text", "text": "hello " },
              { "type": "emote", "text": "Kappa", "emote": { "id": "25" } },
              { "type": "text", "text": " world" }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `парсит id, автора, цвет и роли`() {
        val message = ChatMessageParser.message(JSONObject(sample))

        assertEquals("abc-123", message.id)
        assertEquals("Cool_User", message.author.displayName)
        assertEquals((0xFF shl 24) or 0x00FF7F, message.author.color)
        assertTrue(ChatRole.MODERATOR in message.author.roles)
        assertTrue(ChatRole.SUBSCRIBER in message.author.roles)
        assertEquals(ChatRole.MODERATOR, message.author.primaryRole)
    }

    @Test
    fun `раскладывает фрагменты и эмоут в картинку`() {
        val message = ChatMessageParser.message(JSONObject(sample))

        assertEquals(3, message.fragments.size)
        val emote = message.fragments[1]
        assertTrue(emote is MessageFragment.Emote)
        assertEquals("Kappa", (emote as MessageFragment.Emote).name)
        assertTrue(emote.imageUrl!!.contains("/25/"))
        assertEquals("hello Kappa world", message.text)
    }
}
