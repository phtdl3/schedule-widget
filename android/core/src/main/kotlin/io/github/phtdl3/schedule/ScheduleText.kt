package io.github.phtdl3.schedule

import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Подписи виджета. Названия месяцев и дней заданы явно, чтобы текст не зависел
 * от языка системы на телефоне.
 */
object ScheduleText {
    private val weekdays = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    private val months = listOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )

    /** "1 ч 25 мин", "2 ч", "5 мин". Секунды округляются вверх, минимум — 1 мин. */
    fun duration(value: Duration): String {
        val minutes = maxOf(1, (value.seconds + 59) / 60)
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "$rest мин"
            rest == 0L -> "$hours ч"
            else -> "$hours ч $rest мин"
        }
    }

    fun startsIn(value: Duration): String = "через ${duration(value)}"

    fun endsIn(value: Duration): String = "идёт, ещё ${duration(value)}"

    /** "пт 13 ноября" */
    fun date(day: LocalDate): String =
        "${weekdays[day.dayOfWeek.value - 1]} ${day.dayOfMonth} ${months[day.monthValue - 1]}"

    fun todayTitle(today: LocalDate): String = "Сегодня, ${date(today)}"

    /** Когда начнётся следующая пара: отсчёт, если сегодня; иначе день и время. */
    fun nextStart(overview: DayOverview): String? {
        val next = overview.next ?: return null
        val today = overview.now.toLocalDate()
        return when (ChronoUnit.DAYS.between(today, next.date)) {
            0L -> startsIn(Duration.between(overview.now, next.startsAt))
            1L -> "завтра в ${next.start}"
            else -> "${date(next.date)} в ${next.start}"
        }
    }

    fun typeShort(type: LessonType): String? = when (type) {
        LessonType.LECTURE -> "лек"
        LessonType.PRACTICE -> "пр"
        LessonType.LAB -> "лаб"
        LessonType.OTHER -> null
    }

    fun typeFull(type: LessonType): String? = when (type) {
        LessonType.LECTURE -> "Лекция"
        LessonType.PRACTICE -> "Практика"
        LessonType.LAB -> "Лабораторная"
        LessonType.OTHER -> null
    }
}
