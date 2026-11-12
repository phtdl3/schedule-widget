package io.github.phtdl3.schedulewidget

import android.content.Context
import io.github.phtdl3.schedule.Schedule
import io.github.phtdl3.schedule.ScheduleFormatException
import io.github.phtdl3.schedule.ScheduleJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Подгруппа студента. Пока константа; позже станет настройкой. */
const val SUBGROUP = 2

/**
 * Расписание: загрузка с GitHub Pages и кэш в файле приложения.
 * Виджет и экран читают только кэш, поэтому работают без интернета.
 */
class ScheduleRepository private constructor(context: Context) {
    private val cacheFile = File(context.filesDir, "schedule.json")
    private val loadLock = Mutex()
    private var loaded = false

    private val _schedule = MutableStateFlow<Schedule?>(null)
    val schedule: StateFlow<Schedule?> = _schedule

    /** Когда расписание последний раз успешно скачано, мс; null — ни разу. */
    val downloadedAt: Long? get() = cacheFile.lastModified().takeIf { it > 0 }

    /** Читает кэш с диска один раз за жизнь процесса. */
    suspend fun ensureLoaded() = loadLock.withLock {
        if (loaded) return@withLock
        _schedule.value = withContext(Dispatchers.IO) { readCache() }
        loaded = true
    }

    private fun readCache(): Schedule? = try {
        if (cacheFile.exists()) ScheduleJson.parse(cacheFile.readText()) else null
    } catch (_: IOException) {
        null
    } catch (_: ScheduleFormatException) {
        null
    }

    /** Скачивает свежее расписание. При любой ошибке кэш остаётся прежним. */
    suspend fun refresh(): RefreshResult = withContext(Dispatchers.IO) {
        try {
            val text = download()
            val parsed = ScheduleJson.parse(text)
            if (parsed.lessons.isEmpty()) {
                return@withContext RefreshResult.Failed("в расписании нет пар")
            }
            // Сначала во временный файл: сбой посреди записи не испортит кэш.
            val temporary = File(cacheFile.path + ".tmp")
            temporary.writeText(text)
            if (!temporary.renameTo(cacheFile)) {
                cacheFile.writeText(text)
                temporary.delete()
            }
            _schedule.value = parsed
            loadLock.withLock { loaded = true }
            RefreshResult.Updated
        } catch (error: IOException) {
            RefreshResult.Failed("нет связи с сервером")
        } catch (error: ScheduleFormatException) {
            RefreshResult.Failed(error.message ?: "файл расписания повреждён")
        }
    }

    private fun download(): String {
        val connection = URL(SCHEDULE_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    sealed interface RefreshResult {
        data object Updated : RefreshResult
        data class Failed(val reason: String) : RefreshResult
    }

    companion object {
        const val SCHEDULE_URL = "https://phtdl3.github.io/schedule-widget/schedule.json"

        @Volatile
        private var instance: ScheduleRepository? = null

        fun get(context: Context): ScheduleRepository =
            instance ?: synchronized(this) {
                instance ?: ScheduleRepository(context.applicationContext).also { instance = it }
            }
    }
}
