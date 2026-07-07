package n7.bondcast.obs

import org.json.JSONObject

// op-коды obs-websocket v5
internal const val OP_HELLO = 0
internal const val OP_IDENTIFY = 1
internal const val OP_IDENTIFIED = 2
internal const val OP_EVENT = 5
internal const val OP_REQUEST = 6
internal const val OP_RESPONSE = 7

/** WebSocket close code «неверный пароль» из спеки obs-websocket. */
internal const val CLOSE_AUTH_FAILED = 4009

/** Конверт сообщения: op + полезная нагрузка d. */
internal fun parseEnvelope(text: String): Pair<Int, JSONObject> {
    val message = JSONObject(text)
    return message.getInt("op") to message.getJSONObject("d")
}

/**
 * Identify (op 1) в ответ на Hello (op 0). Блок authentication добавляется,
 * только если сервер прислал challenge; eventSubscriptions не задаём —
 * по умолчанию приходят все не-high-volume события.
 */
internal fun identifyJson(helloData: JSONObject, password: String): String {
    val d = JSONObject().put("rpcVersion", 1)
    helloData.optJSONObject("authentication")?.let { auth ->
        if (password.isBlank()) throw ObsAuthException("OBS требует пароль, а он не задан")
        d.put(
            "authentication",
            obsAuthToken(password, auth.getString("salt"), auth.getString("challenge")),
        )
    }
    return JSONObject().put("op", OP_IDENTIFY).put("d", d).toString()
}

internal fun requestJson(type: String, id: String, data: JSONObject? = null): String {
    val d = JSONObject()
        .put("requestType", type)
        .put("requestId", id)
    if (data != null) d.put("requestData", data)
    return JSONObject().put("op", OP_REQUEST).put("d", d).toString()
}

/**
 * Ответ на запрос (op 7): при успехе возвращает responseData (может быть пустым),
 * при отказе кидает ObsRequestException.
 */
internal fun unwrapResponse(d: JSONObject): JSONObject {
    val status = d.getJSONObject("requestStatus")
    if (!status.getBoolean("result")) {
        throw ObsRequestException(status.getInt("code"), status.optString("comment").ifEmpty { null })
    }
    return d.optJSONObject("responseData") ?: JSONObject()
}

/** GetSceneList: имена сцен сверху вниз как в OBS (сервер шлёт их в обратном порядке). */
internal fun parseSceneList(responseData: JSONObject): Pair<List<String>, String?> {
    val array = responseData.getJSONArray("scenes")
    val scenes = (0 until array.length())
        .map { array.getJSONObject(it) }
        .sortedByDescending { it.getInt("sceneIndex") }
        .map { it.getString("sceneName") }
    val current = responseData.optString("currentProgramSceneName").ifEmpty { null }
    return scenes to current
}

internal fun parseStats(responseData: JSONObject): ObsStats = ObsStats(
    cpuUsage = responseData.getDouble("cpuUsage"),
    activeFps = responseData.getDouble("activeFps"),
    renderSkippedFrames = responseData.getLong("renderSkippedFrames"),
    renderTotalFrames = responseData.getLong("renderTotalFrames"),
    outputSkippedFrames = responseData.getLong("outputSkippedFrames"),
    outputTotalFrames = responseData.getLong("outputTotalFrames"),
)

/** Сырой статус стрима: kbps контроллер считает сам по дельте outputBytes. */
internal data class StreamStatusPayload(
    val active: Boolean,
    val timecode: String,
    val outputBytes: Long,
)

internal fun parseStreamStatus(responseData: JSONObject): StreamStatusPayload = StreamStatusPayload(
    active = responseData.getBoolean("outputActive"),
    timecode = responseData.optString("outputTimecode", "00:00:00"),
    outputBytes = responseData.optLong("outputBytes"),
)

internal fun parseRecordStatus(responseData: JSONObject): ObsRecordStatus = ObsRecordStatus(
    active = responseData.getBoolean("outputActive"),
    timecode = responseData.optString("outputTimecode", "00:00:00"),
)

// извлечение полей событий (op 5)
internal fun eventSceneName(eventData: JSONObject): String = eventData.getString("sceneName")

internal fun eventOutputActive(eventData: JSONObject): Boolean = eventData.getBoolean("outputActive")
