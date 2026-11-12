package io.github.phtdl3.schedulewidget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.phtdl3.schedule.DayOverview
import io.github.phtdl3.schedule.Lesson
import io.github.phtdl3.schedule.LessonType
import io.github.phtdl3.schedule.ScheduleText
import io.github.phtdl3.schedule.overview
import java.time.Instant

class ScheduleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScheduleWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshWorker.schedule(context)
    }
}

class ScheduleWidget : GlanceAppWidget() {
    // Точный размер нужен, чтобы посчитать, сколько строк списка помещается.
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repository = ScheduleRepository.get(context)
        repository.ensureLoaded()

        provideContent {
            // Чтение состояния подписывает виджет на update(): при каждом обновлении
            // содержимое пересчитывается заново с текущим временем.
            currentState<Preferences>()
            val schedule by repository.schedule.collectAsState()
            WidgetContent(schedule?.overview(Instant.now(), SUBGROUP))
        }
    }
}

/** Цвета из согласованного макета (README, «Дизайн виджета»): светлая / тёмная тема. */
private object Palette {
    private fun pair(day: Long, night: Long) = ColorProvider(day = Color(day), night = Color(night))

    val background = pair(0xFFFFFFFF, 0xFF2C2C2A)
    val primary = pair(0xFF2C2C2A, 0xFFF1EFE8)
    val secondary = pair(0xFF444441, 0xFFD3D1C7)
    val muted = pair(0xFF5F5E5A, 0xFFB4B2A9)
    val currentRow = pair(0xFFF1EFE8, 0xFF444441)
    val divider = pair(0xFFD3D1C7, 0xFF5F5E5A)

    val lectureFill = pair(0xFFE6F1FB, 0xFF0C447C)
    val lectureText = pair(0xFF0C447C, 0xFFB5D4F4)
    val practiceFill = pair(0xFFFAEEDA, 0xFF633806)
    val practiceText = pair(0xFF633806, 0xFFFAC775)
    val labFill = pair(0xFFEAF3DE, 0xFF27500A)
    val labText = pair(0xFF27500A, 0xFFC0DD97)

    fun fill(type: LessonType): ColorProvider? = when (type) {
        LessonType.LECTURE -> lectureFill
        LessonType.PRACTICE -> practiceFill
        LessonType.LAB -> labFill
        LessonType.OTHER -> null
    }

    fun text(type: LessonType): ColorProvider = when (type) {
        LessonType.LECTURE -> lectureText
        LessonType.PRACTICE -> practiceText
        LessonType.LAB -> labText
        LessonType.OTHER -> secondary
    }
}

private fun style(color: ColorProvider, size: Int = 13, bold: Boolean = false) = TextStyle(
    color = color,
    fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
)

/** Примерная высота всего, что выше списка, вместе с отступами виджета. */
private const val HEADER_HEIGHT_DP = 150f

/** Примерная высота одной строки списка. */
private const val ROW_HEIGHT_DP = 34f

/** Glance не разрешает больше 10 элементов в одном столбце. */
private const val MAX_ROWS = 10

@Composable
private fun WidgetContent(overview: DayOverview?) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(Palette.background)
            .cornerRadius(24.dp)
            .padding(16.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        if (overview == null) {
            Text("Расписание ещё не загружено", style = style(Palette.primary, 16, bold = true))
            Spacer(GlanceModifier.height(4.dp))
            Text("Нажмите, чтобы открыть приложение и загрузить его", style = style(Palette.secondary))
            return@Column
        }
        // Glance молча отбрасывает всё после 10-го элемента в одном столбце,
        // поэтому верхний блок и список живут в собственных вложенных столбцах.
        Column(GlanceModifier.fillMaxWidth()) { NextLesson(overview) }
        Spacer(GlanceModifier.height(12.dp))
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(Palette.divider)) {}
        Spacer(GlanceModifier.height(8.dp))
        Text(
            ScheduleText.todayTitle(overview.now.toLocalDate()),
            style = style(Palette.secondary, 12),
            modifier = GlanceModifier.padding(horizontal = 8.dp),
        )
        Spacer(GlanceModifier.height(4.dp))
        if (overview.today.isEmpty()) {
            Text(
                "Сегодня пар нет",
                style = style(Palette.primary),
                modifier = GlanceModifier.padding(horizontal = 8.dp),
            )
        } else {
            // Обычные строки вместо прокручиваемого списка: виджет сам оставляет
            // актуальные пары, если все не помещаются, и прокручивать его не нужно.
            val capacity = ((LocalSize.current.height.value - HEADER_HEIGHT_DP) / ROW_HEIGHT_DP).toInt()
            Column(GlanceModifier.fillMaxWidth()) {
                overview.visibleToday(capacity.coerceIn(1, MAX_ROWS)).forEach { lesson ->
                    LessonRow(lesson, overview)
                }
            }
        }
    }
}

@Composable
private fun NextLesson(overview: DayOverview) {
    val next = overview.next
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Следующая пара",
            style = style(Palette.secondary, 12),
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(ScheduleText.nextStart(overview) ?: "", style = style(Palette.primary, bold = true))
    }
    Spacer(GlanceModifier.height(4.dp))
    if (next == null) {
        Text("В расписании больше нет пар", style = style(Palette.primary, 16, bold = true))
        return
    }
    Text(next.subject, style = style(Palette.primary, 20, bold = true), maxLines = 2)
    Spacer(GlanceModifier.height(4.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        val label = ScheduleText.typeFull(next.type)
        val fill = Palette.fill(next.type)
        if (label != null && fill != null) {
            Box(
                modifier = GlanceModifier
                    .background(fill)
                    .cornerRadius(6.dp)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(label, style = style(Palette.text(next.type), 12, bold = true))
            }
            Spacer(GlanceModifier.width(8.dp))
        }
        Text(
            listOfNotNull(next.room, "${next.start}–${next.end}").joinToString(" · "),
            style = style(Palette.secondary),
            maxLines = 1,
        )
    }
}

@Composable
private fun LessonRow(lesson: Lesson, overview: DayOverview) {
    val isCurrent = lesson.id == overview.current?.id
    val isPast = !lesson.endsAt.isAfter(overview.now)
    val textColor = if (isPast) Palette.muted else Palette.primary
    val sideColor = if (isPast) Palette.muted else Palette.secondary

    var modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
    if (isCurrent) modifier = modifier.background(Palette.currentRow).cornerRadius(12.dp)

    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Text(lesson.start.toString(), style = style(sideColor), modifier = GlanceModifier.width(42.dp))
        TypeTag(lesson.type)
        Spacer(GlanceModifier.width(8.dp))
        Column(GlanceModifier.defaultWeight()) {
            Text(lesson.subject, style = style(textColor, bold = isCurrent), maxLines = 2)
            val endsIn = overview.currentEndsIn
            if (isCurrent && endsIn != null) {
                Text(ScheduleText.endsIn(endsIn), style = style(Palette.secondary, 12))
            }
        }
        Spacer(GlanceModifier.width(8.dp))
        Text(lesson.room ?: "", style = style(sideColor), maxLines = 1)
    }
}

/** Цветная метка типа с подписью; у записей без типа на её месте пустое место той же ширины. */
@Composable
private fun TypeTag(type: LessonType) {
    val label = ScheduleText.typeShort(type)
    val fill = Palette.fill(type)
    if (label == null || fill == null) {
        Spacer(GlanceModifier.width(36.dp))
        return
    }
    Box(
        modifier = GlanceModifier.width(36.dp).background(fill).cornerRadius(6.dp).padding(vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = style(Palette.text(type), 12, bold = true))
    }
}
