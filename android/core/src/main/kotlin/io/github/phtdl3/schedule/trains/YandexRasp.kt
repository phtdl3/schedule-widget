package io.github.phtdl3.schedule.trains

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.DateTimeException
import java.time.LocalDate
import java.time.OffsetDateTime

class TrainsFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * API Яндекс Расписаний, запрос «Расписание рейсов между станциями».
 * Документация: https://yandex.ru/dev/rasp/doc/ru/reference/schedule-point-point
 */
object YandexRasp {
    private const val SEARCH_URL = "https://api.rasp.yandex-net.ru/v3.0/search/"

    /** Строка, которую условия API требуют показывать рядом с данными. */
    const val ATTRIBUTION = "Данные предоставлены сервисом Яндекс Расписания"
    const val ATTRIBUTION_URL = "https://rasp.yandex.ru/"

    private val json = Json { ignoreUnknownKeys = true }

    /** Ключ в адрес не входит: он передаётся заголовком Authorization и не попадает в журналы. */
    fun searchUrl(from: Station, to: Station, date: LocalDate): String =
        "$SEARCH_URL?from=${from.code}&to=${to.code}&date=$date" +
            "&transport_types=suburban&limit=200&lang=ru_RU"

    /** Один рейс из ответа API, только нужные поля. */
    data class Segment(
        val uid: String,
        val number: String,
        val title: String,
        val startDate: String,
        val departureEpoch: Long,
        val arrivalEpoch: Long,
        val stops: String?,
    ) {
        /** Один и тот же поезд в двух ответах узнаётся по нитке и дате отправления. */
        val key: String get() = "$uid@$startDate"
    }

    fun parseSegments(text: String): List<Segment> {
        val response = try {
            json.decodeFromString<ApiResponse>(text)
        } catch (error: SerializationException) {
            throw TrainsFormatException("ответ Яндекс Расписаний не разобран: ${error.message}", error)
        } catch (error: IllegalArgumentException) {
            throw TrainsFormatException("ответ Яндекс Расписаний не разобран: ${error.message}", error)
        }
        response.error?.let { throw TrainsFormatException("Яндекс Расписания: ${it.text ?: "ошибка"}") }
        val segments = response.segments
            ?: throw TrainsFormatException("в ответе Яндекс Расписаний нет списка рейсов")
        return segments.map { segment ->
            try {
                Segment(
                    uid = segment.thread.uid,
                    number = segment.thread.number.orEmpty(),
                    title = segment.thread.title.orEmpty(),
                    startDate = segment.startDate ?: segment.departure.take(10),
                    departureEpoch = OffsetDateTime.parse(segment.departure).toEpochSecond(),
                    arrivalEpoch = OffsetDateTime.parse(segment.arrival).toEpochSecond(),
                    stops = segment.stops?.takeIf { it.isNotBlank() },
                )
            } catch (error: DateTimeException) {
                throw TrainsFormatException("некорректное время рейса ${segment.thread.uid}", error)
            }
        }
    }

    /**
     * Сводит два ответа по одному направлению в список поездов.
     *
     * Все поезда идут через Нижегородскую, часть — ещё и через Курский. Поезду, который
     * есть в ответе по Курскому, достаётся Курский и его время; остальным — Нижегородская.
     */
    fun merge(
        direction: Direction,
        viaKursky: List<Segment>,
        viaNizhegorodskaya: List<Segment>,
    ): List<Train> {
        val kurskyKeys = viaKursky.mapTo(HashSet()) { it.key }
        val trains = viaKursky.map { it.toTrain(direction, Station.KURSKY) } +
            viaNizhegorodskaya
                .filter { it.key !in kurskyKeys }
                .map { it.toTrain(direction, Station.NIZHEGORODSKAYA) }
        return trains.sortedBy { it.departureEpoch }
    }

    private fun Segment.toTrain(direction: Direction, moscow: Station) = Train(
        uid = key,
        number = number,
        title = title,
        direction = direction,
        moscow = moscow,
        departureEpoch = departureEpoch,
        arrivalEpoch = arrivalEpoch,
        stops = stops,
    )

    @Serializable
    private data class ApiResponse(
        val segments: List<ApiSegment>? = null,
        val error: ApiError? = null,
    )

    @Serializable
    private data class ApiError(val text: String? = null)

    @Serializable
    private data class ApiSegment(
        val thread: ApiThread,
        val departure: String,
        val arrival: String,
        val stops: String? = null,
        @SerialName("start_date") val startDate: String? = null,
    )

    @Serializable
    private data class ApiThread(
        val uid: String,
        val number: String? = null,
        val title: String? = null,
    )
}
