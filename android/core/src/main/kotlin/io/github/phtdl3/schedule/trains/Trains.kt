package io.github.phtdl3.schedule.trains

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Все три станции в одном часовом поясе. */
val TRAINS_ZONE: ZoneId = ZoneId.of("Europe/Moscow")

/** Станции виджета. [code] — код в Яндекс Расписаниях, [label] — подпись на плашке. */
enum class Station(val code: String, val label: String) {
    GORENKI("s9601665", "Горенки"),
    KURSKY("s2000001", "Курский"),
    NIZHEGORODSKAYA("s9601835", "Нижегор."),
}

enum class Direction { TO_MOSCOW, FROM_MOSCOW }

/**
 * Одна электричка между Горенками и Москвой.
 *
 * [moscow] — её московская станция: Курский вокзал, если поезд через него идёт,
 * иначе Нижегородская. Время хранится в секундах эпохи, чтобы не зависеть от пояса.
 */
@Serializable
data class Train(
    val uid: String,
    val number: String,
    val title: String,
    val direction: Direction,
    val moscow: Station,
    val departureEpoch: Long,
    val arrivalEpoch: Long,
    val stops: String? = null,
) {
    val departure: Instant get() = Instant.ofEpochSecond(departureEpoch)
    val arrival: Instant get() = Instant.ofEpochSecond(arrivalEpoch)
    val departureTime: LocalTime get() = departure.atZone(TRAINS_ZONE).toLocalTime()
    val arrivalTime: LocalTime get() = arrival.atZone(TRAINS_ZONE).toLocalTime()
    val departureDate: LocalDate get() = departure.atZone(TRAINS_ZONE).toLocalDate()
    val travelTime: Duration get() = Duration.between(departure, arrival)

    val from: Station get() = if (direction == Direction.FROM_MOSCOW) moscow else Station.GORENKI
    val to: Station get() = if (direction == Direction.FROM_MOSCOW) Station.GORENKI else moscow
}

/** Сохранённое на телефоне расписание электричек в обе стороны на несколько дней. */
@Serializable
data class TrainTimetable(
    val version: Int = VERSION,
    val fetchedAtEpoch: Long,
    val trains: List<Train>,
) {
    /** Ещё не ушедшие электрички в нужную сторону, ближайшая первой. */
    fun upcoming(direction: Direction, at: Instant, limit: Int): List<Train> =
        trains.asSequence()
            .filter { it.direction == direction && it.departure.isAfter(at) }
            .sortedBy { it.departureEpoch }
            .take(limit)
            .toList()

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val VERSION = 1

        // encodeDefaults: номер версии должен попадать в файл, иначе проверка версии бессмысленна.
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Читает сохранённый файл; null, если он повреждён или записан другой версией приложения. */
        fun decode(text: String): TrainTimetable? = try {
            json.decodeFromString(serializer(), text).takeIf { it.version == VERSION }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** Насколько скоро отправление: от этого зависит цвет плашки с отсчётом. */
enum class Urgency { FAR, SOON, NOW }

object TrainText {
    private const val SOON_MINUTES = 30
    private const val NOW_MINUTES = 10
    private val COUNTDOWN_LIMIT: Duration = Duration.ofHours(12)

    /** Минуты до отправления с округлением вверх, как на плашке. */
    private fun minutesLeft(train: Train, at: Instant): Long =
        (Duration.between(at, train.departure).seconds + 59) / 60

    fun urgency(train: Train, at: Instant): Urgency {
        val minutes = minutesLeft(train, at)
        return when {
            minutes <= NOW_MINUTES -> Urgency.NOW
            minutes <= SOON_MINUTES -> Urgency.SOON
            else -> Urgency.FAR
        }
    }

    /** "через 17 мин", "через 1 ч 5 мин"; если ждать больше 12 часов — "завтра" или дата. */
    fun countdown(train: Train, at: Instant): String {
        val left = Duration.between(at, train.departure)
        if (left <= COUNTDOWN_LIMIT) {
            val minutes = maxOf(1, minutesLeft(train, at))
            val hours = minutes / 60
            val rest = minutes % 60
            return when {
                hours == 0L -> "через $rest мин"
                rest == 0L -> "через $hours ч"
                else -> "через $hours ч $rest мин"
            }
        }
        val today = at.atZone(TRAINS_ZONE).toLocalDate()
        return if (train.departureDate == today.plusDays(1)) {
            "завтра"
        } else {
            "%02d.%02d".format(train.departureDate.dayOfMonth, train.departureDate.monthValue)
        }
    }

    /** Время в пути: "33 мин". */
    fun travelTime(train: Train): String = "${train.travelTime.toMinutes()} мин"

    fun directionLabel(direction: Direction): String = when (direction) {
        Direction.TO_MOSCOW -> "В Москву"
        Direction.FROM_MOSCOW -> "Из Москвы"
    }
}
