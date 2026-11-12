package io.github.phtdl3.schedule

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.DateTimeException

class ScheduleFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Разбор schedule.json, который публикует парсер (см. README, «Формат schedule.json»). */
object ScheduleJson {
    const val SUPPORTED_SCHEMA = 1

    private val json = Json {
        // Парсер может добавлять поля и типы занятий, старое приложение не должно падать.
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun parse(text: String): Schedule {
        val schedule = try {
            json.decodeFromString<Schedule>(text)
        } catch (error: SerializationException) {
            throw ScheduleFormatException("schedule.json не разобран: ${error.message}", error)
        } catch (error: IllegalArgumentException) {
            throw ScheduleFormatException("schedule.json не разобран: ${error.message}", error)
        } catch (error: DateTimeException) {
            throw ScheduleFormatException("в schedule.json некорректная дата или время", error)
        }
        if (schedule.schemaVersion != SUPPORTED_SCHEMA) {
            throw ScheduleFormatException(
                "версия формата ${schedule.schemaVersion}, приложение понимает $SUPPORTED_SCHEMA",
            )
        }
        try {
            schedule.zone
        } catch (error: DateTimeException) {
            throw ScheduleFormatException("неизвестный часовой пояс ${schedule.timezone}", error)
        }
        return schedule.copy(
            lessons = schedule.lessons.sortedWith(
                compareBy({ it.date }, { it.start }, { it.subgroup ?: 0 }, { it.id }),
            ),
        )
    }
}
