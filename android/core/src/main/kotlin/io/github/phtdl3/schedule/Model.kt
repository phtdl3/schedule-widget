package io.github.phtdl3.schedule

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

@Serializable
enum class LessonType {
    @SerialName("lecture") LECTURE,
    @SerialName("practice") PRACTICE,
    @SerialName("lab") LAB,
    @SerialName("other") OTHER,
}

/** Одна пара из schedule.json. Дата и время — по часовому поясу расписания. */
@Serializable
data class Lesson(
    val id: Long,
    @Serializable(LocalDateSerializer::class) val date: LocalDate,
    @Serializable(LocalTimeSerializer::class) val start: LocalTime,
    @Serializable(LocalTimeSerializer::class) val end: LocalTime,
    val subject: String,
    // Тип, которого приложение ещё не знает, превращается в OTHER (см. ScheduleJson).
    val type: LessonType = LessonType.OTHER,
    /** Номер подгруппы; null — пара для всей группы. */
    val subgroup: Int? = null,
    val room: String? = null,
    val teacher: String? = null,
    val teacherShort: String? = null,
    val slot: Int? = null,
    val week: Int? = null,
    val substitution: Boolean = false,
) {
    val startsAt: LocalDateTime get() = date.atTime(start)
    val endsAt: LocalDateTime get() = date.atTime(end)
}

@Serializable
data class Group(val id: Long? = null, val name: String? = null)

@Serializable
data class Schedule(
    val schemaVersion: Int,
    val group: Group = Group(),
    val timezone: String = "Europe/Moscow",
    val generatedAt: String? = null,
    val sourceUpdatedAt: String? = null,
    val lessons: List<Lesson> = emptyList(),
) {
    val zone: ZoneId get() = ZoneId.of(timezone)
}

internal object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}

internal object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor = PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}
