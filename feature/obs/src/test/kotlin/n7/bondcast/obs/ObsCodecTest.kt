package n7.bondcast.obs

import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ObsCodecTest {

    @Test
    fun `identify без challenge не содержит authentication`() {
        val hello = JSONObject("""{"obsWebSocketVersion":"5.5.0","rpcVersion":1}""")
        val identify = JSONObject(identifyJson(hello, password = ""))
        assertEquals(OP_IDENTIFY, identify.getInt("op"))
        val d = identify.getJSONObject("d")
        assertEquals(1, d.getInt("rpcVersion"))
        assertFalse(d.has("authentication"))
    }

    @Test
    fun `identify с challenge содержит корректный токен`() {
        val hello = JSONObject(
            """{"rpcVersion":1,"authentication":{"salt":"c2FsdA==","challenge":"Y2hhbGxlbmdl"}}""",
        )
        val d = JSONObject(identifyJson(hello, password = "supersecret")).getJSONObject("d")
        assertEquals("W9C7NVmerPX51e7i9FKYqUWhC3AHNYLlEhkHOq1yktQ=", d.getString("authentication"))
    }

    @Test
    fun `challenge при пустом пароле — сразу ObsAuthException`() {
        val hello = JSONObject(
            """{"rpcVersion":1,"authentication":{"salt":"s","challenge":"c"}}""",
        )
        assertFailsWith<ObsAuthException> { identifyJson(hello, password = "") }
    }

    @Test
    fun `envelope разбирается на op и d`() {
        val (op, d) = parseEnvelope("""{"op":5,"d":{"eventType":"StreamStateChanged"}}""")
        assertEquals(OP_EVENT, op)
        assertEquals("StreamStateChanged", d.getString("eventType"))
    }

    @Test
    fun `requestJson собирает конверт запроса`() {
        val json = JSONObject(requestJson("SetCurrentProgramScene", "id-1", JSONObject().put("sceneName", "Game")))
        assertEquals(OP_REQUEST, json.getInt("op"))
        val d = json.getJSONObject("d")
        assertEquals("SetCurrentProgramScene", d.getString("requestType"))
        assertEquals("id-1", d.getString("requestId"))
        assertEquals("Game", d.getJSONObject("requestData").getString("sceneName"))
    }

    @Test
    fun `успешный ответ разворачивается в responseData`() {
        val d = JSONObject(
            """{"requestId":"x","requestType":"GetStats","requestStatus":{"result":true,"code":100},
               "responseData":{"cpuUsage":1.5}}""",
        )
        assertEquals(1.5, unwrapResponse(d).getDouble("cpuUsage"))
    }

    @Test
    fun `отказ сервера превращается в ObsRequestException с кодом`() {
        val d = JSONObject(
            """{"requestId":"x","requestType":"StartStream","requestStatus":
               {"result":false,"code":500,"comment":"Output already active"}}""",
        )
        val error = assertFailsWith<ObsRequestException> { unwrapResponse(d) }
        assertEquals(500, error.code)
    }

    @Test
    fun `сцены сортируются как в OBS — сервер шлёт обратный порядок`() {
        val (scenes, current) = parseSceneList(
            JSONObject(
                """{"currentProgramSceneName":"Game","scenes":[
                   {"sceneName":"Пауза","sceneIndex":0},
                   {"sceneName":"Game","sceneIndex":1},
                   {"sceneName":"Старт","sceneIndex":2}]}""",
            ),
        )
        assertEquals(listOf("Старт", "Game", "Пауза"), scenes)
        assertEquals("Game", current)
    }

    @Test
    fun `parseSceneList без текущей сцены отдаёт null`() {
        val (_, current) = parseSceneList(JSONObject("""{"scenes":[]}"""))
        assertNull(current)
    }

    @Test
    fun `статусы стрима и записи читают outputActive и timecode`() {
        val stream = parseStreamStatus(
            JSONObject("""{"outputActive":true,"outputTimecode":"00:01:02.003","outputBytes":123456}"""),
        )
        assertTrue(stream.active)
        assertEquals("00:01:02.003", stream.timecode)
        assertEquals(123456L, stream.outputBytes)

        val record = parseRecordStatus(JSONObject("""{"outputActive":false}"""))
        assertFalse(record.active)
    }

    @Test
    fun `parseStats читает все поля`() {
        val stats = parseStats(
            JSONObject(
                """{"cpuUsage":12.5,"activeFps":60.0,"renderSkippedFrames":3,"renderTotalFrames":1000,
                   "outputSkippedFrames":1,"outputTotalFrames":900,"memoryUsage":500.0}""",
            ),
        )
        assertEquals(12.5, stats.cpuUsage)
        assertEquals(60.0, stats.activeFps)
        assertEquals(3L, stats.renderSkippedFrames)
        assertEquals(900L, stats.outputTotalFrames)
    }
}
