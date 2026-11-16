package io.github.phtdl3.schedule.trains

import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Образцы — урезанные ответы API за 17 ноября 2026: вечер из Москвы (15:10–20:00)
 * и утро в Москву (08:00–10:10).
 */
class TrainsTest {
    private fun segments(name: String) = YandexRasp.parseSegments(
        checkNotNull(javaClass.getResource("/rasp_$name.json")).readText(),
    )

    private val fromMoscow = YandexRasp.merge(Direction.FROM_MOSCOW, segments("k_g"), segments("n_g"))
    private val toMoscow = YandexRasp.merge(Direction.TO_MOSCOW, segments("g_k"), segments("g_n"))
    private val timetable = TrainTimetable(fetchedAtEpoch = 0, trains = fromMoscow + toMoscow)

    private fun moscow(hour: Int, minute: Int, day: Int = 17): Instant =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, TRAINS_ZONE).toInstant()

    private fun List<Train>.brief() = map { "${it.from.label} ${it.departureTime} ${it.arrivalTime} ${it.to.label}" }

    // --- Разбор ответа API ---

    @Test
    fun parsesSegments() {
        val first = segments("k_g").first()
        assertEquals("6754/6753", first.number)
        assertEquals("Апрелевка — Балашиха", first.title)
        assertEquals(moscow(15, 12).epochSecond, first.departureEpoch)
        assertEquals(moscow(15, 45).epochSecond, first.arrivalEpoch)
    }

    @Test
    fun reportsApiError() {
        val error = assertFailsWith<TrainsFormatException> {
            YandexRasp.parseSegments("""{"error": {"text": "Не указан ключ", "http_code": 400}}""")
        }
        assertTrue("Не указан ключ" in error.message.orEmpty())
    }

    @Test
    fun rejectsGarbage() {
        assertFailsWith<TrainsFormatException> { YandexRasp.parseSegments("<html>502</html>") }
        assertFailsWith<TrainsFormatException> { YandexRasp.parseSegments("{}") }
    }

    @Test
    fun searchUrlHasNoKey() {
        assertEquals(
            "https://api.rasp.yandex-net.ru/v3.0/search/?from=s2000001&to=s9601665&date=2026-11-17" +
                "&transport_types=suburban&limit=200&lang=ru_RU",
            YandexRasp.searchUrl(Station.KURSKY, Station.GORENKI, LocalDate.of(2026, 11, 17)),
        )
    }

    // --- Одна московская станция на поезд ---

    @Test
    fun fromMoscowUsesKurskyWhenTrainCallsThere() {
        assertEquals(
            listOf(
                "Курский 15:12 15:45 Горенки",
                "Курский 15:57 16:30 Горенки",
                "Курский 16:13 16:46 Горенки",
                "Курский 16:57 17:30 Горенки",
                "Нижегор. 17:34 18:00 Горенки",
                "Нижегор. 18:21 18:45 Горенки",
                "Курский 18:57 19:30 Горенки",
                "Курский 19:37 20:10 Горенки",
            ),
            fromMoscow.brief(),
        )
    }

    @Test
    fun toMoscowUsesKurskyArrivalWhenTrainGoesThere() {
        assertEquals(
            listOf(
                "Горенки 08:05 08:27 Нижегор.",
                "Горенки 08:30 09:02 Курский",
                "Горенки 08:49 09:22 Курский",
                "Горенки 09:05 09:27 Нижегор.",
                "Горенки 09:45 10:17 Курский",
                "Горенки 10:05 10:37 Курский",
            ),
            toMoscow.brief(),
        )
    }

    @Test
    fun eachTrainAppearsOnce() {
        assertEquals(fromMoscow.size, fromMoscow.map { it.uid }.toSet().size)
        assertEquals(toMoscow.size, toMoscow.map { it.uid }.toSet().size)
    }

    // --- Сохранение на телефоне ---

    @Test
    fun timetableSurvivesSaving() {
        assertEquals(timetable, TrainTimetable.decode(timetable.encode()))
        assertTrue("\"version\":1" in timetable.encode())
    }

    @Test
    fun brokenOrForeignCacheIsIgnored() {
        assertEquals(null, TrainTimetable.decode("не json"))
        assertEquals(null, TrainTimetable.decode(timetable.copy(version = 99).encode()))
    }

    // --- Ближайшие электрички ---

    @Test
    fun upcomingSkipsDepartedTrainsAndRespectsDirection() {
        val next = timetable.upcoming(Direction.FROM_MOSCOW, moscow(16, 40), limit = 3)
        assertEquals(
            listOf("Курский 16:57 17:30 Горенки", "Нижегор. 17:34 18:00 Горенки", "Нижегор. 18:21 18:45 Горенки"),
            next.brief(),
        )
    }

    @Test
    fun trainLeavesTheListAtDepartureTime() {
        assertEquals("16:57", timetable.upcoming(Direction.FROM_MOSCOW, moscow(16, 56), 1).single().departureTime.toString())
        assertEquals("17:34", timetable.upcoming(Direction.FROM_MOSCOW, moscow(16, 57), 1).single().departureTime.toString())
    }

    @Test
    fun nothingUpcomingAfterLastTrain() {
        assertEquals(emptyList(), timetable.upcoming(Direction.TO_MOSCOW, moscow(12, 0), 5))
    }

    // --- Подписи и цвет отсчёта ---

    @Test
    fun countdownAndUrgency() {
        val train = timetable.upcoming(Direction.FROM_MOSCOW, moscow(16, 0), 10)
            .first { it.departureTime.toString() == "16:57" }

        assertEquals("через 57 мин", TrainText.countdown(train, moscow(16, 0)))
        assertEquals(Urgency.FAR, TrainText.urgency(train, moscow(16, 26)))   // 31 минута
        assertEquals(Urgency.SOON, TrainText.urgency(train, moscow(16, 27)))  // ровно 30
        assertEquals("через 17 мин", TrainText.countdown(train, moscow(16, 40)))
        assertEquals(Urgency.SOON, TrainText.urgency(train, moscow(16, 46)))  // 11 минут
        assertEquals(Urgency.NOW, TrainText.urgency(train, moscow(16, 47)))   // ровно 10
        assertEquals("через 10 мин", TrainText.countdown(train, moscow(16, 47)))
        // Меньше минуты до отправления — всё ещё "1 мин", а не "0 мин".
        assertEquals("через 1 мин", TrainText.countdown(train, moscow(16, 56).plusSeconds(30)))
    }

    @Test
    fun longWaitsShowHoursOrDay() {
        val train = timetable.upcoming(Direction.TO_MOSCOW, moscow(0, 0), 1).single() // 08:05
        assertEquals("через 3 ч 5 мин", TrainText.countdown(train, moscow(5, 0)))
        assertEquals("через 2 ч", TrainText.countdown(train, moscow(6, 5)))
        assertEquals("завтра", TrainText.countdown(train, moscow(19, 0, day = 16)))
        assertEquals("17.11", TrainText.countdown(train, moscow(19, 0, day = 15)))
        assertEquals(Urgency.FAR, TrainText.urgency(train, moscow(19, 0, day = 16)))
    }

    @Test
    fun travelTimeAndLabels() {
        val train = timetable.upcoming(Direction.FROM_MOSCOW, moscow(17, 0), 1).single() // 17:34 с Нижегородской
        assertEquals("26 мин", TrainText.travelTime(train))
        assertEquals(Station.NIZHEGORODSKAYA, train.from)
        assertEquals(Station.GORENKI, train.to)
        assertEquals("Из Москвы", TrainText.directionLabel(Direction.FROM_MOSCOW))
    }
}
