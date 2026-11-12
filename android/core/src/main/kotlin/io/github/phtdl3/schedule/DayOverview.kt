package io.github.phtdl3.schedule

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

/** Всё, что показывает виджет в конкретный момент времени. */
data class DayOverview(
    /** Текущий момент по часовому поясу расписания. */
    val now: LocalDateTime,
    /** Пары на сегодня, по порядку. */
    val today: List<Lesson>,
    /** Пара, которая идёт прямо сейчас. */
    val current: Lesson?,
    /** Ближайшая ещё не начавшаяся пара; может быть в другой день. */
    val next: Lesson?,
) {
    val nextStartsIn: Duration? get() = next?.let { Duration.between(now, it.startsAt) }
    val currentEndsIn: Duration? get() = current?.let { Duration.between(now, it.endsAt) }
}

/**
 * Состояние расписания на момент [at].
 *
 * [subgroup] — подгруппа студента: показываются её пары и пары всей группы.
 * null — показывать все подгруппы.
 */
fun Schedule.overview(at: Instant, subgroup: Int?): DayOverview {
    val now = at.atZone(zone).toLocalDateTime()
    val mine = lessons.filter { it.subgroup == null || subgroup == null || it.subgroup == subgroup }
    val today = mine.filter { it.date == now.toLocalDate() }
    return DayOverview(
        now = now,
        today = today,
        // Начало включается, конец нет: в 16:00 пара 14:30–16:00 уже закончилась.
        current = today.firstOrNull { !now.isBefore(it.startsAt) && now.isBefore(it.endsAt) },
        next = mine.firstOrNull { it.startsAt.isAfter(now) },
    )
}
