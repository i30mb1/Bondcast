package n7.bondcast.chat.twitch

import n7.bondcast.chat.ChatSource

/**
 * Источник чата Twitch поверх общего [EventSubConnection]. Реализует агностичный
 * [ChatSource], сводя channel.chat.* к доменным событиям.
 */
public fun twitchChatSource(session: TwitchSession, connection: EventSubConnection): ChatSource = TwitchChatSourceWithLogging(TwitchChatSourceImpl(session, connection))
