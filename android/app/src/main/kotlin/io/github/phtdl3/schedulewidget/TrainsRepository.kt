package io.github.phtdl3.schedulewidget

import android.content.Context
import io.github.phtdl3.schedule.trains.Direction
import io.github.phtdl3.schedule.trains.Station
import io.github.phtdl3.schedule.trains.TRAINS_ZONE
import io.github.phtdl3.schedule.trains.Train
import io.github.phtdl3.schedule.trains.TrainTimetable
import io.github.phtdl3.schedule.trains.TrainsFormatException
import io.github.phtdl3.schedule.trains.YandexRasp
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
import java.time.Instant
import java.time.LocalDate

/**
 * Электрички: загрузка из API Яндекс Расписаний и временный кэш в файле приложения.
 * Данные никуда не передаются и хранятся только на телефоне.
 */
class TrainsRepository private constructor(context: Context) {
    private val cacheFile = File(context.filesDir, "trains.json")
    private val prefs = context.getSharedPreferences("trains", Context.MODE_PRIVATE)
    private val loadLock = Mutex()
    private var loaded = false

    private val _timetable = MutableStateFlow<TrainTimetable?>(null)
    val timetable: StateFlow<TrainTimetable?> = _timetable

    private val _direction = MutableStateFlow(
        runCatching { Direction.valueOf(prefs.getString(KEY_DIRECTION, null).orEmpty()) }
            .getOrDefault(Direction.TO_MOSCOW),
    )

    /** Выбранное в виджете направление; запоминается между запусками. */
    val direction: StateFlow<Direction> = _direction

    fun setDirection(value: Direction) {
        _direction.value = value
        prefs.edit().putString(KEY_DIRECTION, value.name).apply()
    }

    suspend fun ensureLoaded() = loadLock.withLock {
        if (loaded) return@withLock
        _timetable.value = withContext(Dispatchers.IO) {
            try {
                if (cacheFile.exists()) TrainTimetable.decode(cacheFile.readText()) else null
            } catch (_: IOException) {
                null
            }
        }
        loaded = true
    }

    /**
     * Загружает электрички на [DAYS] дней в обе стороны: 4 запроса на день.
     * Если не удался хотя бы один запрос, кэш остаётся прежним.
     */
    suspend fun refresh(): RefreshResult = withContext(Dispatchers.IO) {
        val key = BuildConfig.YANDEX_RASP_KEY
        if (key.isBlank()) {
            return@withContext RefreshResult.Failed("приложение собрано без ключа API")
        }
        try {
            val today = Instant.now().atZone(TRAINS_ZONE).toLocalDate()
            val trains = ArrayList<Train>()
            for (offset in 0 until DAYS) {
                val date = today.plusDays(offset.toLong())
                trains += YandexRasp.merge(
                    Direction.FROM_MOSCOW,
                    viaKursky = fetch(Station.KURSKY, Station.GORENKI, date, key),
                    viaNizhegorodskaya = fetch(Station.NIZHEGORODSKAYA, Station.GORENKI, date, key),
                )
                trains += YandexRasp.merge(
                    Direction.TO_MOSCOW,
                    viaKursky = fetch(Station.GORENKI, Station.KURSKY, date, key),
                    viaNizhegorodskaya = fetch(Station.GORENKI, Station.NIZHEGORODSKAYA, date, key),
                )
            }
            if (trains.isEmpty()) return@withContext RefreshResult.Failed("в ответе нет электричек")

            // Запросы за соседние дни могут вернуть один и тот же ночной поезд.
            val timetable = TrainTimetable(
                fetchedAtEpoch = Instant.now().epochSecond,
                trains = trains.distinctBy { Triple(it.direction, it.uid, it.departureEpoch) },
            )
            val temporary = File(cacheFile.path + ".tmp")
            temporary.writeText(timetable.encode())
            if (!temporary.renameTo(cacheFile)) {
                cacheFile.writeText(timetable.encode())
                temporary.delete()
            }
            _timetable.value = timetable
            loadLock.withLock { loaded = true }
            RefreshResult.Updated
        } catch (error: IOException) {
            RefreshResult.Failed(error.message?.takeIf { it.startsWith("Яндекс") } ?: "нет связи с Яндекс Расписаниями")
        } catch (error: TrainsFormatException) {
            RefreshResult.Failed(error.message ?: "непонятный ответ Яндекс Расписаний")
        }
    }

    private fun fetch(from: Station, to: Station, date: LocalDate, key: String): List<YandexRasp.Segment> {
        val connection = URL(YandexRasp.searchUrl(from, to, date)).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Authorization", key)
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException(
                    when (code) {
                        401, 403 -> "Яндекс не принял ключ API (код $code)"
                        429 -> "Яндекс: превышен лимит запросов"
                        else -> "Яндекс ответил кодом $code"
                    },
                )
            }
            return YandexRasp.parseSegments(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    sealed interface RefreshResult {
        data object Updated : RefreshResult
        data class Failed(val reason: String) : RefreshResult
    }

    companion object {
        /** Сегодня и ещё два дня: хватает, чтобы пережить сутки без интернета. */
        const val DAYS = 3
        private const val KEY_DIRECTION = "direction"

        @Volatile
        private var instance: TrainsRepository? = null

        fun get(context: Context): TrainsRepository =
            instance ?: synchronized(this) {
                instance ?: TrainsRepository(context.applicationContext).also { instance = it }
            }
    }
}
