package io.github.phtdl3.schedule

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Образец — реальные пары группы за 6, 13, 14 и 16 ноября 2026 (15-е — воскресенье). */
class ScheduleTest {
    private val schedule = ScheduleJson.parse(
        checkNotNull(javaClass.getResource("/schedule_sample.json")).readText(),
    )

    private fun moscow(day: Int, hour: Int, minute: Int): Instant =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, ZoneId.of("Europe/Moscow")).toInstant()

    // --- Разбор JSON ---

    @Test
    fun parsesLessons() {
        assertEquals(15, schedule.lessons.size)
        assertEquals("26о-090303-ПИ/1", schedule.group.name)
        val lesson = schedule.lessons.first { it.id == 17060911L }
        assertEquals(LocalDate.of(2026, 11, 6), lesson.date)
        assertEquals(LocalTime.of(14, 30), lesson.start)
        assertEquals(LocalTime.of(16, 0), lesson.end)
        assertEquals("Программирование", lesson.subject)
        assertEquals(LessonType.LAB, lesson.type)
        assertEquals(2, lesson.subgroup)
        assertEquals("В-6-07", lesson.room)
        assertEquals("Айгумов Тимур Гаджиевич", lesson.teacher)
    }

    @Test
    fun toleratesUnknownFieldsAndTypes() {
        val parsed = ScheduleJson.parse(
            """
            {"schemaVersion": 1, "somethingNew": true, "lessons": [
              {"id": 1, "date": "2026-11-13", "start": "09:00", "end": "10:30",
               "subject": "Экзамен", "type": "exam", "futureField": 42}
            ]}
            """,
        )
        assertEquals(LessonType.OTHER, parsed.lessons.single().type)
    }

    @Test
    fun sortsLessons() {
        val parsed = ScheduleJson.parse(
            """
            {"schemaVersion": 1, "lessons": [
              {"id": 2, "date": "2026-11-13", "start": "12:45", "end": "14:15", "subject": "Б"},
              {"id": 1, "date": "2026-11-13", "start": "09:00", "end": "10:30", "subject": "А"}
            ]}
            """,
        )
        assertEquals(listOf(1L, 2L), parsed.lessons.map { it.id })
    }

    @Test
    fun rejectsUnsupportedSchema() {
        assertFailsWith<ScheduleFormatException> {
            ScheduleJson.parse("""{"schemaVersion": 2, "lessons": []}""")
        }
    }

    @Test
    fun rejectsBrokenInput() {
        assertFailsWith<ScheduleFormatException> { ScheduleJson.parse("<html>502</html>") }
        assertFailsWith<ScheduleFormatException> {
            ScheduleJson.parse(
                """{"schemaVersion": 1, "lessons": [
                {"id": 1, "date": "13.11.2026", "start": "09:00", "end": "10:30", "subject": "А"}]}""",
            )
        }
    }

    // --- Что идёт сейчас и что дальше ---

    @Test
    fun breakBetweenLessons() {
        // Пятница 13 ноября, 13:05, подгруппа 2: лабы первой подгруппы не показываются.
        val overview = schedule.overview(moscow(13, 13, 5), subgroup = 2)
        assertEquals(listOf("Высшая математика", "Спортивно-секционный модуль"), overview.today.map { it.subject })
        assertNull(overview.current)
        assertEquals("Высшая математика", overview.next?.subject)
        assertEquals(Duration.ofMinutes(85), overview.nextStartsIn)
    }

    @Test
    fun lessonInProgress() {
        // То же время для подгруппы 1: идёт лабораторная 12:45–14:15.
        val overview = schedule.overview(moscow(13, 13, 5), subgroup = 1)
        assertEquals(4, overview.today.size)
        assertEquals(17059697L, overview.current?.id)
        assertEquals(Duration.ofMinutes(70), overview.currentEndsIn)
        assertEquals("Высшая математика", overview.next?.subject)
    }

    @Test
    fun lessonStartIsInclusiveAndEndIsExclusive() {
        val atStart = schedule.overview(moscow(13, 14, 30), subgroup = 2)
        assertEquals("Высшая математика", atStart.current?.subject)
        assertEquals("Спортивно-секционный модуль", atStart.next?.subject)

        val atEnd = schedule.overview(moscow(13, 16, 0), subgroup = 2)
        assertNull(atEnd.current)
        assertEquals("Спортивно-секционный модуль", atEnd.next?.subject)
    }

    @Test
    fun eveningShowsTomorrow() {
        val overview = schedule.overview(moscow(13, 18, 0), subgroup = 2)
        assertEquals(2, overview.today.size)
        assertNull(overview.current)
        assertEquals(LocalDate.of(2026, 11, 14), overview.next?.date)
        assertEquals(LocalTime.of(10, 25), overview.next?.start)
    }

    @Test
    fun dayWithoutLessons() {
        // Воскресенье 15 ноября.
        val overview = schedule.overview(moscow(15, 12, 0), subgroup = 2)
        assertEquals(emptyList(), overview.today)
        assertNull(overview.current)
        assertEquals(LocalDate.of(2026, 11, 16), overview.next?.date)
    }

    @Test
    fun nothingAhead() {
        // Понедельник 16-го после двух общих пар; третья пара — у первой подгруппы.
        val overview = schedule.overview(moscow(16, 12, 0), subgroup = 2)
        assertEquals(2, overview.today.size)
        assertNull(overview.current)
        assertNull(overview.next)
        assertNull(overview.nextStartsIn)
    }

    @Test
    fun noSubgroupFilterShowsEverything() {
        val overview = schedule.overview(moscow(6, 15, 0), subgroup = null)
        assertEquals(4, overview.today.size)
    }

    @Test
    fun usesScheduleTimezoneNotDeviceTimezone() {
        // 10:05 UTC — это 13:05 в Москве, где бы ни находился телефон.
        val overview = schedule.overview(Instant.parse("2026-11-13T10:05:00Z"), subgroup = 2)
        assertEquals(LocalTime.of(13, 5), overview.now.toLocalTime())
        assertEquals(Duration.ofMinutes(85), overview.nextStartsIn)
    }

    // --- Сколько пар показывать, если не помещаются все ---

    @Test
    fun showsWholeDayWhenItFits() {
        val overview = schedule.overview(moscow(13, 13, 5), subgroup = 1)
        assertEquals(overview.today, overview.visibleToday(capacity = 4))
        assertEquals(overview.today, overview.visibleToday(capacity = 10))
    }

    @Test
    fun dropsFinishedLessonsFirst() {
        // 13:05, подгруппа 1: пара 10:25 прошла, 12:45 идёт, впереди 14:30 и 16:15.
        val overview = schedule.overview(moscow(13, 13, 5), subgroup = 1)
        assertEquals(
            listOf("12:45", "14:30", "16:15"),
            overview.visibleToday(capacity = 3).map { it.start.toString() },
        )
        // Не помещаются даже оставшиеся: показываем ближайшие.
        assertEquals(
            listOf("12:45", "14:30"),
            overview.visibleToday(capacity = 2).map { it.start.toString() },
        )
    }

    @Test
    fun showsLastLessonsWhenDayIsOver() {
        val overview = schedule.overview(moscow(13, 20, 0), subgroup = 1)
        assertEquals(
            listOf("14:30", "16:15"),
            overview.visibleToday(capacity = 2).map { it.start.toString() },
        )
        assertEquals(emptyList(), overview.visibleToday(capacity = 0))
    }

    // --- Подписи ---

    @Test
    fun durationText() {
        assertEquals("5 мин", ScheduleText.duration(Duration.ofMinutes(5)))
        assertEquals("1 ч", ScheduleText.duration(Duration.ofMinutes(60)))
        assertEquals("1 ч 25 мин", ScheduleText.duration(Duration.ofMinutes(85)))
        // Округление вверх: за 30 секунд до пары — "1 мин", а не "0 мин".
        assertEquals("1 мин", ScheduleText.duration(Duration.ofSeconds(30)))
        assertEquals("2 мин", ScheduleText.duration(Duration.ofSeconds(61)))
    }

    @Test
    fun countdownTexts() {
        assertEquals("через 1 ч 25 мин", ScheduleText.startsIn(Duration.ofMinutes(85)))
        assertEquals("идёт, ещё 1 ч 10 мин", ScheduleText.endsIn(Duration.ofMinutes(70)))
    }

    @Test
    fun dateTexts() {
        assertEquals("пт 13 ноября", ScheduleText.date(LocalDate.of(2026, 11, 13)))
        assertEquals("Сегодня, вс 15 ноября", ScheduleText.todayTitle(LocalDate.of(2026, 11, 15)))
    }

    @Test
    fun nextStartText() {
        assertEquals(
            "через 1 ч 25 мин",
            ScheduleText.nextStart(schedule.overview(moscow(13, 13, 5), subgroup = 2)),
        )
        assertEquals(
            "завтра в 10:25",
            ScheduleText.nextStart(schedule.overview(moscow(13, 18, 0), subgroup = 2)),
        )
        assertEquals(
            "пн 16 ноября в 08:30",
            ScheduleText.nextStart(schedule.overview(moscow(14, 18, 0), subgroup = 2)),
        )
        assertNull(ScheduleText.nextStart(schedule.overview(moscow(16, 12, 0), subgroup = 2)))
    }
}
