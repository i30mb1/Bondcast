package n7.bondcast.logging

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

private const val TAG = "SessionLog"

/** Одно поколение лога. Два поколения — верхняя граница вложения, ~2 МБ. */
private const val MAX_FILE_BYTES = 1024L * 1024L

private const val LOG_DIR_NAME = "logs"

internal class SessionLogImpl(private val context: Context) : SessionLog {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var writer: SessionLogWriter? = null
    private var process: Process? = null
    private var job: Job? = null

    override fun start() {
        if (job != null) return
        val target = SessionLogWriter(File(context.cacheDir, LOG_DIR_NAME), MAX_FILE_BYTES)
        writer = target
        deviceSummary(context).lines().forEach(target::append)
        job = scope.launch {
            runCatching {
                // --pid своего процесса: чужие логи приложению всё равно не отдадут начиная
                // с Android 4.1, а так в файл не попадает шум всей системы
                val started = ProcessBuilder("logcat", "-v", "time", "--pid=${android.os.Process.myPid()}")
                    .redirectErrorStream(true)
                    .start()
                process = started
                started.inputStream.bufferedReader().forEachLine(target::append)
            }.onFailure { Log.w(TAG, "читатель logcat остановлен: $it") }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        // именно destroy закрывает stdin читателя — без этого корутина висит на readLine
        runCatching { process?.destroy() }
        process = null
        runCatching { writer?.close() }
    }

    override fun dump(header: String, secrets: List<String>): File? {
        val files = writer?.files().orEmpty()
        if (files.isEmpty()) return null
        val shareDir = File(context.cacheDir, SHARE_DIR_NAME).apply { mkdirs() }
        val out = File(shareDir, SHARE_FILE_NAME)
        val body = files.joinToString("\n") { file -> runCatching { file.readText() }.getOrDefault("") }
        val raw = if (header.isBlank()) body else header.trimEnd() + "\n\n" + body
        return runCatching {
            out.writeText(redactLog(raw, secrets))
            out
        }.getOrNull()
    }
}
